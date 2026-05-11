# Sanshain Maven Plugin

The Sanshain Maven Plugin allows microservices to interact with the Sanshain service to manage and distribute OpenAPI specifications during the build process.

## Goals

- `sanshain:provide`: Uploads a full OpenAPI specification to the Sanshain service. Defaults to the `initialize` phase.
- `sanshain:require`: Downloads specific endpoint snippets from the Sanshain service. Defaults to the `generate-sources` phase.

## Quick Start

Add the plugin to your `pom.xml`:

```xml
<plugin>
    <groupId>io.github.paxel.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>1.8.0</version>
    <executions>
        <execution>
            <goals>
                <goal>require</goal>
                <goal>provide</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Then create a `sanshain.yaml` in your project root (see below).

## Configuration via `sanshain.yaml`

The plugin automatically looks for a `sanshain.yaml` file in your project's root directory. This keeps your `pom.xml` clean.

```yaml
sanshainUrl: https://sanshain.example.com
serviceName: order-service
timeout: 120
compression: true
bestEffort: true

provides:
  - file: src/main/resources/openapi.yaml
  - file: src/main/resources/order.proto
    apiType: proto

requires:
  - serviceName: user-service
    outputDirectory: target/generated-sources/sanshain/user-service
    timeout: 60
    endpoints:
      - method: GET
        path: /api/v1/users
      - method: GET
        path: /api/v1/users/{id}
  - serviceName: inventory-service
    outputDirectory: target/generated-sources/sanshain/inventory-service
    endpoints:
      - method: POST
        path: /api/v1/orders
```

You can override the configuration file location using the `configFile` property:

```xml
<configuration>
    <configFile>${project.basedir}/my-sanshain-config.yaml</configFile>
</configuration>
```

> **Note:** The `token` is intentionally **not** stored in `sanshain.yaml`. See [Authentication](#authentication) below.

## Authentication

The plugin supports token-based authentication via `Authorization: Bearer <token>`. The token is resolved from the following sources (highest priority first):

1. **Environment variable** `$SANSHAIN_TOKEN`
2. **Maven `settings.xml`** — `<server>` element with matching `serverId` (default: `sanshain`)
3. **Maven property** `-Dsanshain.token=...`

If no token is configured, requests are sent without authentication (suitable for local development).

### Token and URL via `settings.xml`

Both the authentication token and the Sanshain service URL can be configured in `settings.xml`. This is the recommended approach for defining where the Sanshain service is running, as it keeps infrastructure configuration out of the repository.

```xml
<settings>
  <servers>
    <server>
      <id>sanshain</id>
      <username>ignored</username>
      <password>san_abc123...</password>
      <configuration>
        <sanshainUrl>https://sanshain.example.com</sanshainUrl>
      </configuration>
    </server>
  </servers>
</settings>
```

The `<id>` must match the `serverId` parameter (default: `sanshain`). The `<password>` field holds the token. The `<sanshainUrl>` in the `<configuration>` block sets the service URL.

**URL resolution order** (highest priority first):
1. Maven property `-Dsanshain.url` (e.g. on the command line or in `pom.xml`)
2. `settings.xml` server `<configuration><sanshainUrl>` (recommended for shared infrastructure)
3. Environment variable `$SANSHAIN_URL`
4. `sanshain.yaml` file in the project root
5. Default: `http://localhost:8080`

This means that if all sources are configured at the same time, the Maven property wins. If no Maven property is set, `settings.xml` takes precedence over the environment variable, which in turn overrides the value from `sanshain.yaml`. If none of these are set, the plugin falls back to `http://localhost:8080`.

## Configuration Reference

### Global Settings

All global settings can be specified in `sanshain.yaml`, overridden via Maven properties, or set as environment variables.

| Setting                     | `sanshain.yaml` | Maven Property           | Env Variable            | Default                                            |
|-----------------------------|-----------------|--------------------------|-------------------------|----------------------------------------------------|
| Sanshain URL                | `sanshainUrl`   | `-Dsanshain.url`         | `$SANSHAIN_URL`         | `http://localhost:8080`                            |
| Sanshain URL (settings.xml) | —               | —                        | —                       | via `<configuration><sanshainUrl>` in server entry |
| Token                       | -               | `-Dsanshain.token`       | `$SANSHAIN_TOKEN`       | — (optional)                                       |
| Timeout (seconds)           | `timeout`       | `-Dsanshain.timeout`     | `$SANSHAIN_TIMEOUT`     | `120`                                              |
| Compression                 | `compression`   | `-Dsanshain.compression` | `$SANSHAIN_COMPRESSION` | `true`                                             |
| Insecure                    | `insecure`      | `-Dsanshain.insecure`    | `$SANSHAIN_INSECURE`    | `false`                                            |
| Best Effort                 | `bestEffort`    | `-Dsanshain.bestEffort`  | `$SANSHAIN_BEST_EFFORT` | `false` (see [Best-Effort Mode](#best-effort-mode)) |
| Strict                      | `strict`        | `-Dsanshain.strict`      | —                       | `false` (see [Strict Mode](#strict-mode))          |
| Service name                | `serviceName`   | `-Dsanshain.service.name`| `$SANSHAIN_SERVICE_NAME`| — (required)                                       |
| Branch                      | —               | `-Dsanshain.branch`      | `$SANSHAIN_BRANCH`      | auto-detected from Git                             |
| Dry-run                     | —               | `-Dsanshain.dry.run`     | —                       | `false`                                            |
| Force                       | —               | `-Dsanshain.force`       | `$SANSHAIN_FORCE`       | `false`                                            |

### Branch Detection

The branch is automatically detected from the local Git repository using JGit. You can override it via `-Dsanshain.branch` or the `SANSHAIN_BRANCH` environment variable. The Maven property takes precedence over the environment variable. If neither is available, it defaults to `main`.

## Goal: `provide`

Uploads the service's API specifications (OpenAPI, AsyncAPI, and/or Proto) to the Sanshain service.

### Parameters

| Parameter     | Property                | Default                                   | Description                                                   |
|---------------|-------------------------|-------------------------------------------|---------------------------------------------------------------|
| `serviceName` | `sanshain.service.name` | —                                         | **Required.** The name of the service providing the API.      |
| `openApiFile` | `sanshain.openapi.file` | `${project.build.directory}/openapi.yaml` | Path to the default OpenAPI YAML file.                        |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080`                   | URL of the Sanshain service.                                  |
| `token`       | `sanshain.token`        | —                                         | Authentication token (prefer `settings.xml` or env variable). |
| `compression` | `sanshain.compression`  | `true`                                    | Enable gzip compression for the upload.                       |
| `insecure`    | `sanshain.insecure`     | `false`                                   | Ignore SSL certificate errors.                                |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                                   | Don't fail the build on server errors (see [Best-Effort Mode](#best-effort-mode)). |
| `serverId`    | `sanshain.serverId`     | `sanshain`                                | Server ID for `settings.xml` token lookup.                    |
| `skip`        | `sanshain.skip`         | `false`                                   | Skip execution of all sanshain goals.                         |
| `skipProvide` | `sanshain.provide.skip` | `false`                                   | Skip execution of the provide goal only.                      |
| `dryRun`      | `sanshain.dry.run`      | `false`                                   | Validate without storing (see [Dry-Run Mode](#dry-run-mode)). |
| `force`       | `sanshain.force`        | `false`                                   | Force mode — override the existing contract (see [Force Mode](#force-mode)). Also settable via `$SANSHAIN_FORCE`. |
| `strict`      | `sanshain.strict`       | `false`                                   | Fail on missing config instead of warning (see [Strict Mode](#strict-mode)). |

These parameters can also be provided via the `provides` list in `sanshain.yaml`:

```yaml
serviceName: my-service
provides:
  - file: src/main/resources/openapi.yaml
    apiType: openapi
  - file: src/main/resources/events.yaml
    apiType: asyncapi
```

## Goal: `require`

Downloads OpenAPI snippets for specific endpoints that this service consumes. The server uses long-polling — the plugin makes a single HTTP request per endpoint and waits for the server to respond (no client-side retry loop).

### Automatic Bundling

When a service has **2 or more endpoints** configured, the plugin automatically uses the `/require-bundle` endpoint instead of making individual requests. This returns a single merged OpenAPI YAML with **deduplicated schemas and components**, solving the problem of duplicate DTOs when generating client code from multiple endpoints of the same service.

- **1 endpoint** → individual `GET /require` call, saved as `{serviceName}_{path}_{method}.yaml`
- **2+ endpoints** → single `POST /require-bundle` call, saved as `{serviceName}_bundle.yaml`

### Parameters

| Parameter     | Property                | Default                 | Description                                                                  |
|---------------|-------------------------|-------------------------|------------------------------------------------------------------------------|
| `serviceName` | `sanshain.service.name` | —                       | **Required.** The name of the client service requesting the endpoints.       |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080` | URL of the Sanshain service.                                                 |
| `token`       | `sanshain.token`        | —                       | Authentication token (prefer `settings.xml` or env variable).                |
| `timeout`     | `sanshain.timeout`      | `120`                   | Global timeout in seconds for server long-polling.                           |
| `compression` | `sanshain.compression`  | `true`                  | Enable gzip compression for downloads.                                       |
| `insecure`    | `sanshain.insecure`     | `false`                 | Ignore SSL certificate errors.                                               |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                 | Don't fail the build on server errors (see [Best-Effort Mode](#best-effort-mode)). |
| `serverId`    | `sanshain.serverId`     | `sanshain`              | Server ID for `settings.xml` token lookup.                                   |
| `skip`        | `sanshain.skip`         | `false`                 | Skip execution of all sanshain goals.                                        |
| `skipRequire` | `sanshain.require.skip` | `false`                 | Skip execution of the require goal only.                                     |
| `dryRun`      | `sanshain.dry.run`      | `false`                 | Validate without recording dependencies (see [Dry-Run Mode](#dry-run-mode)). |
| `strict`      | `sanshain.strict`       | `false`                 | Fail on missing config instead of warning (see [Strict Mode](#strict-mode)). |

The required endpoints are defined in the `requires` section of `sanshain.yaml`:

```yaml
requires:
  - serviceName: user-service
    outputDirectory: target/generated-sources/sanshain
    timeout: 60          # optional, overrides global timeout for this service
    endpoints:
      - method: GET
        path: /api/v1/users/{id}
      - method: GET
        path: /api/v1/users
```

For a single endpoint, the snippet is saved as `{outputDirectory}/{serviceName}_{path}_{method}.yaml` (path slashes are replaced with underscores).

For multiple endpoints (2+), the merged bundle is saved as `{outputDirectory}/{serviceName}_bundle.yaml`.

### How Long-Polling Works

The `timeout` parameter is sent to the server as a query parameter (for individual requests) or in the JSON body (for bundle requests). The server waits up to that many seconds for the requested specification to become available. The client HTTP timeout is set to `timeout + 30s` to allow for network overhead. If the server times out (returns 404), the build fails with a descriptive error.

## Combined Example

If your service both provides an API and consumes other APIs, configure everything in `sanshain.yaml`:

```yaml
sanshainUrl: https://sanshain.example.com
serviceName: order-service

provides:
  - file: src/main/resources/openapi.yaml

requires:
  - serviceName: user-service
    outputDirectory: target/generated-sources/sanshain/user-service
    endpoints:
      - method: GET
        path: /users/{id}
      - method: GET
        path: /users
```

With a minimal `pom.xml` configuration:

```xml
<plugin>
    <groupId>io.github.paxel.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>1.8.0</version>
    <executions>
        <execution>
            <goals>
                <goal>require</goal>
                <goal>provide</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

In this example, `user-service` has 2 endpoints, so the plugin will automatically use `/require-bundle` and save the result as `target/generated-sources/sanshain/user-service_bundle.yaml`.

## v0.13.0 Features

The following features were introduced with Sanshain Service v0.13.0 and are supported starting with plugin version 1.5.0.

### Optimistic Concurrency Control (`baseVersion`)

Add `baseVersion` to your provide configuration to enable conflict detection:

```yaml
provides:
  - file: src/main/resources/openapi.yaml
    baseVersion: 5
```

If the server's current version has advanced beyond your `baseVersion`, the provide call returns `409 Conflict` with a clear error message:

> Concurrent modification detected. Server version has advanced beyond your base_version. Re-run to fetch the latest state.

The plugin automatically tracks the last known version in a local cache file (`target/.sanshain-cache.json`), so after the first successful provide, subsequent builds automatically send the correct `base_version` without manual configuration.

### Provide Response Summary

The server now returns a `202 Accepted` response with a JSON body containing version info and a change summary. The plugin logs a human-readable message after each successful provide:

```
✓ Provided to Sanshain v5: 2 new, 1 updated, 0 deleted endpoints
```

The `version` and `content_hash` from the response are stored in the local cache for use by concurrency control and content caching.

### Client-Side Content Caching (Skip-if-unchanged)

Before uploading, the plugin computes the SHA-256 hash of the spec file and compares it with the hash from the last successful provide (stored in `target/.sanshain-cache.json`). If the hash matches, the API call is skipped entirely:

```
⏭ Spec unchanged (hash match), skipping provide.
```

This saves network roundtrips and is especially valuable in CI pipelines where specs rarely change between builds.

### Require-Side ETag Caching

The plugin stores the `ETag` header from require responses. On subsequent builds, it sends `If-None-Match` with the stored ETag. If the server responds with `304 Not Modified`, the output file is not rewritten:

```
⏭ user-service spec unchanged (304), skipping code generation.
```

This avoids unnecessary code generation and speeds up incremental builds.

### State File

The local cache is stored at `target/.sanshain-cache.json` and is automatically deleted by `mvn clean`. The format is:

```json
{
  "provides": {
    "openapi.yaml": {
      "content_hash": "sha256:abc123...",
      "version": 5,
      "last_provided": "2026-04-25T12:00:00Z"
    }
  },
  "requires": {
    "user-service|main|GET|/api/v1/users": {
      "etag": "\"sha256:def456...\"",
      "last_fetched": "2026-04-25T12:00:00Z"
    }
  }
}
```

## Strict Mode

By default, the plugin warns and skips when configuration is incomplete (e.g., no `serviceName`, no `provides`, or no `requires`). This makes it safe to include both goals in every project even if only one applies.

To restore the old fail-on-missing behavior, enable strict mode:

```bash
mvn verify -Dsanshain.strict=true
```

Or in `pom.xml`:

```xml
<configuration>
    <strict>true</strict>
</configuration>
```

When strict mode is enabled:
- Missing `serviceName` → build failure
- No `provides` configured → build failure
- No `requires` configured → build failure

## Best-Effort Mode

Best-effort mode is designed for scenarios where the Sanshain service is considered a non-critical part of the build, or when the plugin is configured in a **parent POM** across many projects.

When enabled, the plugin will catch and log most execution errors (such as 5xx server errors, connection timeouts, or IO issues) as warnings instead of failing the build. This ensures that a temporary downtime of the Sanshain service does not block your entire CI/CD pipeline.

Enable best-effort mode via the Maven property:

```bash
mvn verify -Dsanshain.bestEffort=true
```

Or in `sanshain.yaml`:

```yaml
bestEffort: true
```

### Key Differences

| Scenario | Default (`bestEffort=false`) | Best-Effort (`bestEffort=true`) |
|----------|-----------------------------|---------------------------------|
| Server 500 error | **Build Fails** | Build Warns & Continues |
| Connection Timeout | **Build Fails** | Build Warns & Continues |
| Invalid `sanshain.yaml` | **Build Fails** | Build Warns & Continues |
| Missing `serviceName` | Warns (unless `strict=true`) | Warns |

> **Note:** Best-effort mode does **not** suppress errors caused by incorrect plugin usage (e.g., invalid Maven parameters that prevent the Mojo from starting). It specifically targets errors occurring during the execution phase (communication with the service).

### Using in Parent POMs

To enable the Sanshain plugin for every child project in a multi-module build, add it to the `<build><plugins>` section of your company-wide parent POM. This ensures that the `provide` and `require` goals are executed for every project during the build.

Combined with `bestEffort` mode, this is completely safe even for projects that do not yet use Sanshain:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.paxel.sanshain</groupId>
            <artifactId>sanshain-maven-plugin</artifactId>
            <version>1.8.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>provide</goal>
                        <goal>require</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <!-- Ensures the build doesn't fail if a project lacks sanshain.yaml or the service is down -->
                <bestEffort>true</bestEffort>
            </configuration>
        </plugin>
    </plugins>
</build>
```

#### How it works:
1. **Inheritance**: Maven automatically applies plugins in `<build><plugins>` to all child modules.
2. **Automatic Execution**: The goals will run in their default phases (`initialize` for provide, `generate-sources` for require).
3. **Safety**: If a child project doesn't have a `sanshain.yaml`, the plugin will log a warning and continue (thanks to `bestEffort`).
4. **Opting Out**: If a specific child project needs to disable the plugin entirely, it can set the skip property in its own `pom.xml`:
   ```xml
   <properties>
       <sanshain.skip>true</sanshain.skip>
   </properties>
   ```

Alternatively, if you only want to configure the default settings without forcing execution in all projects, use `<pluginManagement>` or Maven properties:

```xml
<properties>
    <sanshain.bestEffort>true</sanshain.bestEffort>
</properties>
```

```xml
<pluginManagement>
    <plugins>
        <plugin>
            <groupId>io.github.paxel.sanshain</groupId>
            <artifactId>sanshain-maven-plugin</artifactId>
            <version>1.8.0</version>
            <configuration>
                <bestEffort>true</bestEffort>
            </configuration>
        </plugin>
    </plugins>
</pluginManagement>
```

With `bestEffort` enabled, the plugin will log a warning if `sanshain.yaml` is missing or invalid, and continue the build.

## Force Mode

The `force` flag allows the `provide` goal to override the existing shared contract source with the current upload, even if it would normally be rejected due to conflict detection. This is useful on feature branches where you want to reset the contract to match your local version.

> **Warning:** Force mode will fail on protected branches (e.g., `main`, `master`). The Sanshain service rejects forced uploads on protected branches to prevent accidental overwrites of the canonical contract.

Enable force mode via the Maven property:

```bash
mvn verify -Dsanshain.force=true
```

Or via the environment variable (env-only, not available in `sanshain.yaml`):

```bash
export SANSHAIN_FORCE=true
mvn verify
```

In force mode:
- **Feature branches**: The existing contract is replaced with the uploaded version, bypassing conflict detection.
- **Protected branches**: The server returns a **403 Forbidden** error and the build fails.

## Dry-Run Mode

The plugin supports a dry-run mode that validates requests against the Sanshain service without persisting any data. No specs are stored, and no client dependencies are recorded. This is useful for CI pipelines that need to verify a feature branch would be valid against the main branch before merging.

Enable dry-run mode via the Maven property:

```bash
mvn verify -Dsanshain.dry.run=true
```

In dry-run mode:
- **`provide`**: The OpenAPI spec is parsed and validated (including conflict detection on protected branches), but nothing is stored.
- **`require`**: Endpoint lookups are performed, but no client dependencies are recorded.

## Error Responses

Starting with Sanshain service 0.6.0, error responses include descriptive messages explaining why the request failed. The plugin logs these messages at `ERROR` level before failing the build, making it easier to diagnose issues:

- **409 Conflict on `provide`**: Includes the method, path, branch, and service name (e.g., "DTO changed for GET /users on protected branch 'main' of service 'my-svc'").
- **404 Not Found on `require`/`require-bundle`**: Includes the missing endpoint details (method, path, service name, branch).

## CI Integration

For CI environments, use environment variables to configure the plugin without modifying `sanshain.yaml`:

```bash
export SANSHAIN_URL=https://sanshain.example.com
export SANSHAIN_TOKEN=san_abc123...
export SANSHAIN_BRANCH=feature/my-branch  # optional, auto-detected from Git
export SANSHAIN_FORCE=true                # optional, reset shared contract source
export SANSHAIN_BEST_EFFORT=true          # optional, don't fail on server errors
mvn verify
```

To validate that a feature branch would be compatible with master before merging (dry-run):

```bash
export SANSHAIN_BRANCH=master
mvn verify -Dsanshain.dry.run=true
```

## Integrating with OpenAPI Generator

The downloaded snippets can be used by the `openapi-generator-maven-plugin` to generate client code.

For a **bundled** result (multiple endpoints from the same service):

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.0.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <inputSpec>${project.build.directory}/generated-sources/sanshain/user-service_bundle.yaml</inputSpec>
                <generatorName>java</generatorName>
                <library>resttemplate</library>
                <output>${project.build.directory}/generated-sources/openapi</output>
                <apiPackage>com.example.client.user.api</apiPackage>
                <modelPackage>com.example.client.user.model</modelPackage>
                <generateApiTests>false</generateApiTests>
                <generateModelTests>false</generateModelTests>
            </configuration>
        </execution>
    </executions>
</plugin>
```

For a **single endpoint** result:

```xml
<configuration>
    <inputSpec>${project.build.directory}/generated-sources/sanshain/user-service_api_v1_users_{id}_GET.yaml</inputSpec>
    <!-- ... other config ... -->
</configuration>
```

## Compression

Gzip compression is enabled by default (`compression: true`).

- **Provide (upload):** The request body is gzip-compressed and sent with `Content-Encoding: gzip`.
- **Require (download):** The request includes `Accept-Encoding: gzip`, and the response is automatically decompressed if the server responds with gzip.
- **Require-bundle:** Both the request body and response support gzip compression.

To disable compression, set `compression: false` in `sanshain.yaml` or use `-Dsanshain.compression=false`.

## Debugging

To see detailed debug output from the plugin, run Maven with the `-X` flag:

```bash
mvn -X clean install
```

This will log:
- Resolved configuration values (URL, service name, branch, compression, token presence)
- HTTP request details (method, URL, body size, compression)
- HTTP response details (status code, content encoding, body size)
- Git branch detection results

## Skipping Execution

To temporarily disable the plugin without removing it from your `pom.xml`, use the `sanshain.skip` property:

```bash
mvn clean install -Dsanshain.skip=true
```

This skips both `provide` and `require` goals.

To skip only a specific goal:

```bash
mvn clean install -Dsanshain.provide.skip=true   # skip only provide
mvn clean install -Dsanshain.require.skip=true   # skip only require
```

## License

This project is licensed under the GNU Affero General Public License (AGPL-3.0). See the [LICENSE](LICENSE) file for details.
