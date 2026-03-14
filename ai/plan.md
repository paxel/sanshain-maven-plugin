# Implementation Plan: Sanshain Maven Plugin — Feature Complete

## Current State
- `ProvideMojo`: Reads config + OpenAPI file, detects Git branch via JGit. **No HTTP upload implemented.**
- `RequireMojo`: Reads config + requirements, has polling loop skeleton. **No HTTP call implemented.**
- `SanshainConfig`: Knows `sanshainUrl`, `clientName`, `provide`, `require`. **No token, no compression.**
- No GitHub project, no CI, no release workflow.

## Goal
Feature parity with SanshainService API (`api.yaml`), including:
- Working HTTP communication (provide + require)
- Token-based authentication (via `settings.xml` or env variable, NOT in `sanshain.yaml`)
- Timeout as server-side long-polling parameter (no client-side polling)
- Gzip compression support
- All config overridable via environment variables for CI
- GitHub CI + Maven Central release

## Key Design Decisions
- **Token**: Never stored in `sanshain.yaml`. Provided via Maven `settings.xml` (server credentials) or `$SANSHAIN_TOKEN` env variable. Every user/CI needs their own token.
- **Fallback branch**: Not needed on client side. The server handles fallback internally and automatically.
- **Client-side polling**: Removed completely. The server does long-polling; the client makes a single HTTP request with a timeout parameter and waits.
- **Env variable overrides**: All global config values in `sanshain.yaml` can be overridden by env variables so CI can override user config.

---

## Phase 1: Config Extension (`SanshainConfig.java`)

### 1.1 New Top-Level Fields in `SanshainConfig`
- `timeout` (Integer, default: `120`) — Global timeout for server long-polling in seconds
- `compression` (Boolean, default: `true`) — Enable gzip compression

### 1.2 Config Resolution Order (per field)
Every global config field can come from multiple sources. Priority (highest first):
| Field | Env Variable | Maven Property | settings.xml | sanshain.yaml | Default |
|-------|-------------|----------------|--------------|---------------|---------|
| `sanshainUrl` | `$SANSHAIN_URL` | `-Dsanshain.url` | `<server>` element | `sanshainUrl` | `http://localhost:8080` |
| `token` | `$SANSHAIN_TOKEN` | `-Dsanshain.token` | `<server>` password | ❌ not in yaml | — (optional) |
| `timeout` | `$SANSHAIN_TIMEOUT` | `-Dsanshain.timeout` | — | `timeout` | `120` |
| `compression` | `$SANSHAIN_COMPRESSION` | `-Dsanshain.compression` | — | `compression` | `true` |
| `clientName` | `$SANSHAIN_CLIENT_NAME` | `-Dsanshain.clientName` | — | `clientName` | — (required for require) |

### 1.3 Token via `settings.xml`
```xml
<settings>
  <servers>
    <server>
      <id>sanshain</id>
      <username>ignored</username>
      <password>san_abc123...</password>
    </server>
  </servers>
</settings>
```
The server `<id>` matches a configurable `serverId` (default: `"sanshain"`). The `<password>` field holds the token.

### 1.4 Example `sanshain.yaml`
```yaml
sanshainUrl: https://sanshain.example.com
timeout: 120
compression: true
clientName: my-consumer-service

provide:
  serviceName: my-service
  openApiFile: src/main/resources/openapi.yaml

requires:
  - serviceName: user-service
    outputDirectory: target/generated-sources/sanshain
    timeout: 60
    endpoints:
      - method: GET
        path: /api/v1/users
      - method: GET
        path: /api/v1/users/{id}
  - serviceName: order-service
    outputDirectory: target/generated-sources/sanshain
    endpoints:
      - method: POST
        path: /api/v1/orders
```

Note: `token` is intentionally absent from `sanshain.yaml` — it comes from `settings.xml` or `$SANSHAIN_TOKEN`.

---

## Phase 2: HTTP Communication

### 2.1 `ProvideMojo` — POST /provide
- HTTP POST to `{sanshainUrl}/provide`
- Request body (JSON):
  ```json
  {
    "servicename": "<serviceName>",
    "branch": "<branch>",
    "openapi_yaml": "<file content>"
  }
  ```
- Header: `Authorization: Bearer <token>` (if token is set)
- Header: `Content-Encoding: gzip` + compressed body (if compression=true)
- Header: `Content-Type: application/json`
- Expected responses: 202 (OK), 400 (Bad Request), 409 (Conflict on protected branch)
- Error handling with descriptive log messages

### 2.2 `RequireMojo` — GET /require
- HTTP GET to `{sanshainUrl}/require` with query parameters:
  - `clientname` = clientName
  - `servicename` = requirement.serviceName
  - `branch` = current Git branch
  - `path` = requirement.path
  - `method` = requirement.method
  - `timeout` = timeout (server-side long-polling)
- Header: `Authorization: Bearer <token>` (if token is set)
- Header: `Accept-Encoding: gzip` (if compression=true)
- **No client-side polling** — the server handles waiting via long-polling. The client makes one request per endpoint and blocks until the server responds or times out.
- **No client-side fallback** — the server handles branch fallback internally and automatically.
- Response 200: Save YAML snippet to file at `{outputDirectory}/{serviceName}_{path}_{method}.yaml` (replace path slashes with underscores)
- Response 404 (after server timeout): Throw `MojoExecutionException`
- Client HTTP timeout = server timeout + 30s buffer

### 2.3 HTTP Client
- `java.net.http.HttpClient` (Java 11+) — no additional dependency needed
- Custom helper class `SanshainHttpClient.java`:
  - `postProvide(url, token, serviceName, branch, openapiYaml, compression)` → void (throws on error)
  - `getRequire(url, token, clientName, serviceName, branch, path, method, timeout, compression)` → String (YAML content)
  - Gzip compression/decompression handled internally
  - Logging via Maven log

---

## Phase 3: Token Support

### 3.1 Token Sources (Priority, highest first)
1. Environment variable `$SANSHAIN_TOKEN`
2. Maven `settings.xml` → `<server id="sanshain"><password>...</password></server>`
3. Maven property `-Dsanshain.token=...`

### 3.2 Implementation
- Both mojos resolve token from the three sources in priority order
- Access `settings.xml` via `@Component MavenSession` / `Settings` injection in the Mojo
- Token is sent as `Authorization: Bearer <token>` header
- If no token is set: Requests without auth header (for dev mode of the service)

---

## Phase 4: Timeout Configuration

### 4.1 Timeout Sources (Priority, highest first)
1. Environment variable `$SANSHAIN_TIMEOUT`
2. Requirement-specific timeout in `sanshain.yaml` → `requires.<service>.timeout`
3. Global timeout in `sanshain.yaml` → `timeout`
4. Maven property `-Dsanshain.timeout=...`
5. Default: 120 seconds

### 4.2 Usage
- Timeout is sent as `timeout` query parameter to the server (long-polling)
- No client-side retry loop — the server handles the waiting
- Client HTTP request timeout = server timeout + 30s buffer

---

## Phase 5: Compression Support

### 5.1 Configuration
- `compression: true/false` in `sanshain.yaml`
- Env variable `$SANSHAIN_COMPRESSION`
- Maven property `-Dsanshain.compression=true`
- Default: `true`

### 5.2 Implementation
- **Provide (Upload)**: `Content-Encoding: gzip` header + gzip-compressed request body
- **Require (Download)**: `Accept-Encoding: gzip` header, automatically decompress response if `Content-Encoding: gzip`
- Uses `java.util.zip.GZIPOutputStream` / `GZIPInputStream`

---

## Phase 6: GitHub Project with CI and Release

### 6.1 Repository Setup
- `.github/workflows/ci.yml`: Build + test on push/PR
- `.github/workflows/release.yml`: Maven Central release on tag

### 6.2 CI Workflow (`ci.yml`)
```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - run: mvn verify -B
```

### 6.3 Release Workflow (`release.yml`)
- Trigger: Tag `v*`
- Maven Central publishing via `maven-publish` + Sonatype OSSRH
- GPG signing of artifacts
- Required secrets: `OSSRH_USERNAME`, `OSSRH_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`

### 6.4 POM Adjustments for Maven Central
- `groupId`: `com.sanshain`
- Complete `<developers>`, `<scm>`, `<url>`, `<licenses>` sections
- `maven-source-plugin`, `maven-javadoc-plugin`, `maven-gpg-plugin`
- `nexus-staging-maven-plugin` for Sonatype

---

## Phase 7: Tests

### 7.1 Unit Tests
- `SanshainHttpClientTest`: Mock server (WireMock) for provide/require endpoints
- `ConfigLoaderTest`: Loading extended config with all new fields + env override logic
- `ProvideMojoTest`: Extend with token, compression
- `RequireMojoTest`: Timeout forwarding, no polling

### 7.2 Integration Tests
- `maven-invoker-plugin` or `maven-verifier` with real Maven build
- Mock SanshainService (WireMock) in test setup

---

## Implementation Order

| Step | Description                               | Files                                                         |
|------|-------------------------------------------|---------------------------------------------------------------|
| 1    | Extend config + env override logic        | `SanshainConfig.java`, `ConfigLoader.java`                    |
| 2    | Create HTTP client                        | `SanshainHttpClient.java` (new)                               |
| 3    | ProvideMojo: HTTP upload                  | `ProvideMojo.java`                                            |
| 4    | RequireMojo: HTTP download + file storage | `RequireMojo.java`                                            |
| 5    | Token support (settings.xml + env)        | `ProvideMojo.java`, `RequireMojo.java`                        |
| 6    | Timeout as server parameter               | `RequireMojo.java`                                            |
| 7    | Compression                               | `SanshainHttpClient.java`                                     |
| 8    | Tests                                     | `src/test/java/...`                                           |
| 9    | POM for Maven Central                     | `pom.xml`                                                     |
| 10   | GitHub CI + release workflows             | `.github/workflows/`                                          |
