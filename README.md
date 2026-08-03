# Sanshain Maven Plugin

The Sanshain Maven Plugin allows microservices to interact with the Sanshain service to manage and distribute API specifications (OpenAPI, AsyncAPI, gRPC/Proto) during the build process. A **Producer** provides its complete specification under a producer-declared **version**; a **Consumer** pins an exact version and requires only the endpoint snippets it uses.

## Goals

- `sanshain:provide`: Uploads a full API specification to the Sanshain service under its declared version and stability. Defaults to the `initialize` phase.
- `sanshain:require`: Downloads specific endpoint snippets at exact pinned versions. Defaults to the `initialize` phase.

## Requirements

- **Sanshain service 2.x.** Plugin 2.x speaks Sanshain Service 2.x — this is a clean break from the 1.x branch-based contract; neither side accepts the other's old fields.
- Java 11 or later.

## The 2.0 model in one minute

- **The version lives in the spec file.** For OpenAPI and AsyncAPI it is read from `info.version`; for proto from a mandatory `// sanshain-version: MAJOR.MINOR.PATCH` comment. It must be strict three-part semver — no `v` prefix, no `-SNAPSHOT` suffixes. The plugin never sends a version on provide; the server reads it from the document.
- **Stability is declared on every provide**: `snapshot` (overwritable work-in-progress, expires when unused) or `ga` (immutable; the number is permanently claimed). The default is always `snapshot`; GA is an explicit switch (`-Dsanshain.ga=true` or `SANSHAIN_GA=true`) that CI sets on release pipelines.
- **Consumers pin exact versions.** Every `requires` entry carries a `version`. Resolution prefers GA, falls back only to the same-numbered snapshot, and otherwise fails immediately — no fallback to other versions, no waiting.
- **Branches are gone.** There is no branch detection, no branch parameter, no long-polling, no force mode, and no `baseVersion` optimistic concurrency. GA immutability is the concurrency control.

## Quick Start

Add the plugin to your `pom.xml`:

```xml
<plugin>
    <groupId>io.github.paxel.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>2.0.0</version>
    <executions>
        <execution>
            <phase>initialize</phase>
            <goals>
                <goal>provide</goal>
                <goal>require</goal>
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
compression: true
bestEffort: true

provides:
  - file: src/main/resources/openapi.yaml   # version read from info.version
  - file: src/main/resources/order.proto    # version read from // sanshain-version:
    apiType: proto

requires:
  - serviceName: user-service
    version: 2.3.0
    outputDirectory: target/generated-sources/sanshain/user-service
    endpoints:
      - method: GET
        path: /api/v1/users
      - method: GET
        path: /api/v1/users/{id}
  - serviceName: inventory-service
    version: 1.1.0
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

### Migrating a 1.x `sanshain.yaml`

The plugin validates the configuration at parse time, before any network call, and rejects every leftover 1.x field **by name** with a migration hint:

- A `requires` entry without `version` → add an exact pin, e.g. `version: 1.2.0` (list what's available with `GET /producers/<name>/versions`).
- `branch` (anywhere) → the branch model was removed in Sanshain 2.0; replace with an exact `version` pin.
- `timeout` (global or per-require) → resolution is immediate; there is no long-polling. Remove it.
- `baseVersion` → GA immutability replaced optimistic concurrency. Remove it.
- `releaseBranches` / `stability` in the YAML → stability is declared per build via the [GA switch](#stability-the-ga-switch), not configured in the file.

## Authentication

The plugin supports token-based authentication via `Authorization: Bearer <token>`. The token is resolved from the following sources (highest priority first):

1. **Environment variable** `$SANSHAIN_TOKEN`
2. **Maven `settings.xml`** — `<server>` element with matching `serverId` (default: `sanshain`)
3. **Maven property** `-Dsanshain.token=...`

If no token is configured, requests are sent without authentication (suitable for local development).

Attribution of a provide is the authenticated token identity — there is no client-supplied author field.

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

| Setting                     | `sanshain.yaml` | Maven Property            | Env Variable             | Default |
|-----------------------------|-----------------|---------------------------|--------------------------|---------|
| Sanshain URL                | `sanshainUrl`   | `-Dsanshain.url`          | `$SANSHAIN_URL`          | `http://localhost:8080` |
| Sanshain URL (settings.xml) | —               | —                         | —                        | via `<configuration><sanshainUrl>` in server entry |
| Token                       | —               | `-Dsanshain.token`        | `$SANSHAIN_TOKEN`        | — (optional) |
| GA switch (provide only)    | —               | `-Dsanshain.ga`           | `$SANSHAIN_GA`           | `false` (provide as `snapshot`; see [Stability](#stability-the-ga-switch)) |
| Compression                 | `compression`   | `-Dsanshain.compression`  | `$SANSHAIN_COMPRESSION`  | `true` |
| Insecure                    | `insecure`      | `-Dsanshain.insecure`     | `$SANSHAIN_INSECURE`     | `false` |
| Best Effort                 | `bestEffort`    | `-Dsanshain.bestEffort`   | `$SANSHAIN_BEST_EFFORT`  | `false` (see [Best-Effort Mode](#best-effort-mode)) |
| Strict                      | `strict`        | `-Dsanshain.strict`       | —                        | `false` (see [Strict Mode](#strict-mode)) |
| Service name                | `serviceName`   | `-Dsanshain.service.name` | `$SANSHAIN_SERVICE_NAME` | — (required) |
| Dry-run                     | —               | `-Dsanshain.dry.run`      | —                        | `false` |
| Combine                     | `combine`       | `-Dsanshain.combine`      | `$SANSHAIN_COMBINE`      | `true` (see [Specification Combining/Bundling](#specification-combiningbundling)) |

## Stability: the GA switch

Every provide declares a stability, and the plugin decides it with exactly one rule:

- **Default: `snapshot`.** Overwritable work-in-progress; re-providing the same version replaces it (last writer wins). Snapshots expire when unused.
- **`ga` only via an explicit switch:** the Maven property `-Dsanshain.ga=true` or the environment variable `SANSHAIN_GA=true`. GA versions are immutable forever and permanently claim their number.

There is no git magic and no branch matching — CI simply sets the switch on release/protected-branch pipelines:

```bash
# feature pipeline (default)
mvn verify

# release pipeline
mvn verify -Dsanshain.ga=true
# or
export SANSHAIN_GA=true
mvn verify
```

## Goal: `provide`

Uploads the service's API specifications (OpenAPI, AsyncAPI, and/or Proto) to the Sanshain service. The server reads the version from the document and answers `202 Accepted` with what it stored.

### Parameters

| Parameter     | Property                | Default                                   | Description |
|---------------|-------------------------|-------------------------------------------|-------------|
| `serviceName` | `sanshain.service.name` | —                                         | **Required.** The name of the service providing the API. |
| `openApiFile` | `sanshain.openapi.file` | `${project.build.directory}/openapi.yaml` | Path to the default OpenAPI YAML file. |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080`                   | URL of the Sanshain service. |
| `token`       | `sanshain.token`        | —                                         | Authentication token (prefer `settings.xml` or env variable). |
| `ga`          | `sanshain.ga`           | `false`                                   | Provide as `ga` instead of `snapshot` (see [Stability](#stability-the-ga-switch)). Also settable via `$SANSHAIN_GA`. |
| `compression` | `sanshain.compression`  | `true`                                    | Enable gzip compression for the upload. |
| `insecure`    | `sanshain.insecure`     | `false`                                   | Ignore SSL certificate errors. |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                                   | Don't fail the build on server errors (see [Best-Effort Mode](#best-effort-mode)). |
| `serverId`    | `sanshain.serverId`     | `sanshain`                                | Server ID for `settings.xml` token lookup. |
| `skip`        | `sanshain.skip`         | `false`                                   | Skip execution of all sanshain goals. |
| `skipProvide` | `sanshain.provide.skip` | `false`                                   | Skip execution of the provide goal only. |
| `dryRun`      | `sanshain.dry.run`      | `false`                                   | Validate without storing (see [Dry-Run Mode](#dry-run-mode)). |
| `combine`     | `sanshain.combine`      | `true`                                    | Enable recursive local combining/bundling of multi-file specifications (see [Specification Combining/Bundling](#specification-combiningbundling)). |
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

### Version rules and `409` rejections

The server enforces the version rules at the door, and every rejection is self-service:

- **Idempotency**: re-providing byte-identical content is a no-op (`changes` all zero) — CI re-runs of the same commit never fight.
- **Snapshots** are overwritable in place; **GA is immutable**. A provide against an existing GA version with different content is rejected `409` — the response carries `proposed_version`, the next free number bumped by what actually changed (breaking → major, additive → minor, shape-identical → patch).
- **Semver honesty**: a GA provide whose changes against the highest GA below it are breaking without a major bump is rejected `409` with the correct `proposed_version`.

On a `409` the plugin prints the server's message and the remedy prominently, e.g.:

```
Publish as 1.3.0 — update info.version in openapi.yaml and re-run.
```

The plugin never modifies your spec files — the fix is always a version bump in your own file (for proto: the `// sanshain-version:` comment).

A `400` means the document itself is invalid — most commonly a missing or non-semver version.

## Goal: `require`

Downloads endpoint snippets at exact pinned versions. Resolution is immediate: GA preferred, else the same-numbered snapshot, else failure — nothing waits for a version to appear.

### Automatic Bundling

When a service has **2 or more endpoints** configured, the plugin automatically uses the `/require-bundle` endpoint instead of making individual requests. This returns a single merged spec with **deduplicated schemas and components**, solving the problem of duplicate DTOs when generating client code from multiple endpoints of the same service.

- **1 endpoint** → individual `GET /require` call, saved as `{serviceName}_{path}_{method}.yaml`
- **2+ endpoints** → single `POST /require-bundle` call, saved as `{serviceName}_bundle.yaml`

### Parameters

| Parameter     | Property                | Default                 | Description |
|---------------|-------------------------|-------------------------|-------------|
| `serviceName` | `sanshain.service.name` | —                       | **Required.** The name of the client service requesting the endpoints. |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080` | URL of the Sanshain service. |
| `token`       | `sanshain.token`        | —                       | Authentication token (prefer `settings.xml` or env variable). |
| `compression` | `sanshain.compression`  | `true`                  | Enable gzip compression for downloads. |
| `insecure`    | `sanshain.insecure`     | `false`                 | Ignore SSL certificate errors. |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                 | Don't fail the build on server errors (see [Best-Effort Mode](#best-effort-mode)). |
| `serverId`    | `sanshain.serverId`     | `sanshain`              | Server ID for `settings.xml` token lookup. |
| `skip`        | `sanshain.skip`         | `false`                 | Skip execution of all sanshain goals. |
| `skipRequire` | `sanshain.require.skip` | `false`                 | Skip execution of the require goal only. |
| `dryRun`      | `sanshain.dry.run`      | `false`                 | Validate without recording dependencies (see [Dry-Run Mode](#dry-run-mode)). |
| `strict`      | `sanshain.strict`       | `false`                 | Fail on missing config instead of warning (see [Strict Mode](#strict-mode)). |

The required endpoints are defined in the `requires` section of `sanshain.yaml`; each entry pins an exact version:

```yaml
requires:
  - serviceName: user-service
    version: 2.3.0
    outputDirectory: target/generated-sources/sanshain
    endpoints:
      - method: GET
        path: /api/v1/users/{id}
      - method: GET
        path: /api/v1/users
```

For a single endpoint, the snippet is saved as `{outputDirectory}/{serviceName}_{path}_{method}.yaml` (path slashes are replaced with underscores).

For multiple endpoints (2+), the merged bundle is saved as `{outputDirectory}/{serviceName}_bundle.yaml`.

### Failure modes

The plugin surfaces the two failure modes distinctly:

- **`404` Unknown** — the Producer, or the pinned version, does not exist in either stability. This is a configuration error and fails in milliseconds: fix the `version` pin (list what's available with `GET /producers/<name>/versions`).
- **`410` Absent** — the pinned version exists and deliberately does not include the requested endpoint(s). Provided specs are complete, so absence is a definitive no. For bundles, the whole bundle fails and the body names the missing endpoints.

If a provide or require fails against a server that turns out to be pre-2.0, the plugin checks the instance version once (`GET /version`) and replaces the confusing wire error with a clear message:

```
Sanshain server at https://sanshain.example.com is 1.7.0; this client requires Sanshain 2.x — upgrade the server.
```

## Combined Example

If your service both provides an API and consumes other APIs, configure everything in `sanshain.yaml`:

```yaml
sanshainUrl: https://sanshain.example.com
serviceName: order-service

provides:
  - file: src/main/resources/openapi.yaml

requires:
  - serviceName: user-service
    version: 2.3.0
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
    <version>2.0.0</version>
    <executions>
        <execution>
            <phase>initialize</phase>
            <goals>
                <goal>provide</goal>
                <goal>require</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

In this example, `user-service` has 2 endpoints, so the plugin will automatically use `/require-bundle` and save the result as `target/generated-sources/sanshain/user-service_bundle.yaml`.

## Local Caching

### Provide Response Summary

The server returns a `202 Accepted` response with a JSON body containing the stored version and a change summary. The plugin logs a human-readable message after each successful provide:

```
✓ Provided to Sanshain as 1.4.0 (ga): 2 new, 1 updated, 0 deleted endpoints
```

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

This avoids unnecessary code generation and speeds up incremental builds. A pin on a GA version can never change content; a pin on a snapshot can, which is exactly what the ETag detects.

### State File

The local cache is stored at `target/.sanshain-cache.json` and is automatically deleted by `mvn clean`. The format is:

```json
{
  "provides": {
    "openapi.yaml": {
      "content_hash": "sha256:abc123...",
      "last_provided": "2026-04-25T12:00:00Z"
    }
  },
  "requires": {
    "user-service|2.3.0|GET|/api/v1/users": {
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

Note that an *invalid* `sanshain.yaml` — including leftover 1.x fields or a `requires` entry without a `version` pin — is always a hard error regardless of strict mode (only [Best-Effort Mode](#best-effort-mode) downgrades it to a warning).

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
|----------|------------------------------|---------------------------------|
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
            <version>2.0.0</version>
            <executions>
                <execution>
                    <phase>initialize</phase>
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
2. **Automatic Execution**: The goals will run in their default phase (`initialize`).
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
            <version>2.0.0</version>
            <configuration>
                <bestEffort>true</bestEffort>
            </configuration>
        </plugin>
    </plugins>
</pluginManagement>
```

With `bestEffort` enabled, the plugin will log a warning if `sanshain.yaml` is missing or invalid, and continue the build.

## Dry-Run Mode

The plugin supports a dry-run mode that validates requests against the Sanshain service without persisting any data. No specs are stored, and no client dependencies are recorded. This is useful for CI pipelines that want to verify a spec would be accepted by the version rules before publishing it.

Enable dry-run mode via the Maven property:

```bash
mvn verify -Dsanshain.dry.run=true
```

In dry-run mode:
- **`provide`**: The spec is parsed, its version read, and the provide classified against the version rules (including GA immutability and semver honesty), but nothing is stored.
- **`require`**: Endpoint lookups are performed at the pinned versions, but no dependencies are recorded.

## Specification Combining/Bundling

Large OpenAPI, AsyncAPI, and Protocol Buffers (Protobuf) specifications are often modularized across multiple local files. Since the Sanshain service expects single-file uploads, the plugin provides a **combining/bundling** pre-processing step to recursively resolve relative local references and inline their contents.

When `combine` is enabled, the plugin processes specifications as follows:

### OpenAPI and AsyncAPI (YAML/JSON)
- Recursively inspects fields matching `$ref` that point to local files relative to the parent file.
- Resolves and extracts elements with fragment/JSON-pointer navigation support (e.g., `dto.yaml#/components/schemas/User`).
- Replaces the `$ref` nodes with the recursively-resolved, fully-inlined YAML/JSON object structure.

### Protocol Buffers (Protobuf)
- Recursively finds `import "some_local.proto";` statements.
- Resolves and inlines local `.proto` files, automatically stripping redundant `syntax` and `package` header declarations from the imported files to preserve valid single-file protobuf syntax.
- Non-local standard imports (e.g., `import "google/protobuf/timestamp.proto";`) that do not exist locally relative to the project are skipped and left untouched.

### Infinite Recursion Protection
The combiner implements canonical-path-based circular dependency detection. If any cycles are detected (e.g., File A references File B, which references File A), the plugin fails fast and throws a descriptive `MojoExecutionException` containing the reference cycle path.

### Configuration

You can enable combining at multiple levels (highest priority first):

1. **Granular override per-file** in `sanshain.yaml`:
   ```yaml
   provides:
     - file: src/main/resources/openapi.yaml
       combine: true
   ```
2. **Global Maven Property**:
   ```bash
   mvn verify -Dsanshain.combine=true
   ```
3. **Environment Variable Override**:
   ```bash
   export SANSHAIN_COMBINE=true
   ```
4. **Global YAML Setting** in `sanshain.yaml`:
   ```yaml
   combine: true
   ```

By default, specification combining is enabled (`true`).

## CI Integration

For CI environments, use environment variables to configure the plugin without modifying `sanshain.yaml`:

```bash
export SANSHAIN_URL=https://sanshain.example.com
export SANSHAIN_TOKEN=san_abc123...
export SANSHAIN_BEST_EFFORT=true          # optional, don't fail on server errors
mvn verify
```

On release/protected-branch pipelines, additionally set the GA switch:

```bash
export SANSHAIN_GA=true
mvn verify
```

To validate that a spec would be accepted before actually publishing it (dry-run):

```bash
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
- Resolved configuration values (URL, service name, stability, compression, token presence)
- HTTP request details (method, URL, body size, compression)
- HTTP response details (status code, content encoding, body size)

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
