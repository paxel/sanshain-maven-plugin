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
    <version>0.4.0</version>
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
timeout: 120
compression: true
clientName: order-service

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
  - serviceName: inventory-service
    outputDirectory: target/generated-sources/sanshain
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

### Token via `settings.xml`

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

The `<id>` must match the `serverId` parameter (default: `sanshain`). The `<password>` field holds the token.

## Configuration Reference

### Global Settings

All global settings can be specified in `sanshain.yaml`, overridden via Maven properties, or set as environment variables.

| Setting           | `sanshain.yaml` | Maven Property           | Env Variable            | Default                    |
|-------------------|-----------------|--------------------------|-------------------------|----------------------------|
| Sanshain URL      | `sanshainUrl`   | `-Dsanshain.url`         | `$SANSHAIN_URL`         | `http://localhost:8080`    |
| Token             | ❌ not in yaml   | `-Dsanshain.token`       | `$SANSHAIN_TOKEN`       | — (optional)               |
| Timeout (seconds) | `timeout`       | `-Dsanshain.timeout`     | `$SANSHAIN_TIMEOUT`     | `120`                      |
| Compression       | `compression`   | `-Dsanshain.compression` | `$SANSHAIN_COMPRESSION` | `true`                     |
| Client name       | `clientName`    | `-DclientName`           | `$SANSHAIN_CLIENT_NAME` | — (required for `require`) |
| Branch            | —               | —                        | `$SANSHAIN_BRANCH`      | auto-detected from Git     |

### Branch Detection

The branch is automatically detected from the local Git repository using JGit. You can override it by setting the `SANSHAIN_BRANCH` environment variable. If neither is available, it defaults to `main`.

## Goal: `provide`

Uploads the service's OpenAPI specification to the Sanshain service.

### Parameters

| Parameter     | Property               | Default                                   | Description                                                   |
|---------------|------------------------|-------------------------------------------|---------------------------------------------------------------|
| `serviceName` | `serviceName`          | —                                         | **Required.** The name of the service providing the API.      |
| `openApiFile` | `openApiFile`          | `${project.build.directory}/openapi.yaml` | Path to the OpenAPI YAML file.                                |
| `sanshainUrl` | `sanshain.url`         | `http://localhost:8080`                   | URL of the Sanshain service.                                  |
| `token`       | `sanshain.token`       | —                                         | Authentication token (prefer `settings.xml` or env variable). |
| `compression` | `sanshain.compression` | `true`                                    | Enable gzip compression for the upload.                       |
| `serverId`    | `sanshain.serverId`    | `sanshain`                                | Server ID for `settings.xml` token lookup.                    |
| `skip`        | `sanshain.skip`        | `false`                                   | Skip execution of all sanshain goals.                         |
| `skipProvide` | `sanshain.provide.skip`| `false`                                   | Skip execution of the provide goal only.                      |

These parameters can also be provided via the `provide` section in `sanshain.yaml`:

```yaml
provide:
  serviceName: my-service
  openApiFile: src/main/resources/openapi.yaml
```

## Goal: `require`

Downloads OpenAPI snippets for specific endpoints that this service consumes. The server uses long-polling — the plugin makes a single HTTP request per endpoint and waits for the server to respond (no client-side retry loop).

### Automatic Bundling

When a service has **2 or more endpoints** configured, the plugin automatically uses the `/require-bundle` endpoint instead of making individual requests. This returns a single merged OpenAPI YAML with **deduplicated schemas and components**, solving the problem of duplicate DTOs when generating client code from multiple endpoints of the same service.

- **1 endpoint** → individual `GET /require` call, saved as `{serviceName}_{path}_{method}.yaml`
- **2+ endpoints** → single `POST /require-bundle` call, saved as `{serviceName}_bundle.yaml`

### Parameters

| Parameter     | Property               | Default                 | Description                                                            |
|---------------|------------------------|-------------------------|------------------------------------------------------------------------|
| `clientName`  | `clientName`           | —                       | **Required.** The name of the client service requesting the endpoints. |
| `sanshainUrl` | `sanshain.url`         | `http://localhost:8080` | URL of the Sanshain service.                                           |
| `token`       | `sanshain.token`       | —                       | Authentication token (prefer `settings.xml` or env variable).          |
| `timeout`     | `sanshain.timeout`     | `120`                   | Global timeout in seconds for server long-polling.                     |
| `compression` | `sanshain.compression` | `true`                  | Enable gzip compression for downloads.                                 |
| `serverId`    | `sanshain.serverId`    | `sanshain`              | Server ID for `settings.xml` token lookup.                             |
| `skip`        | `sanshain.skip`        | `false`                 | Skip execution of all sanshain goals.                                  |
| `skipRequire` | `sanshain.require.skip`| `false`                 | Skip execution of the require goal only.                               |

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
clientName: order-service

provide:
  serviceName: order-service
  openApiFile: src/main/resources/openapi.yaml

requires:
  - serviceName: user-service
    outputDirectory: target/generated-sources/sanshain
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
    <version>0.4.0</version>
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

## CI Integration

For CI environments, use environment variables to configure the plugin without modifying `sanshain.yaml`:

```bash
export SANSHAIN_URL=https://sanshain.example.com
export SANSHAIN_TOKEN=san_abc123...
export SANSHAIN_BRANCH=feature/my-branch  # optional, auto-detected from Git
mvn verify
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
