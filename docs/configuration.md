# Configuration Reference

The Sanshain Maven Plugin is designed to be flexible, allowing configuration via `sanshain.yaml`, Maven properties, environment variables, or Maven `settings.xml`.

## `sanshain.yaml`

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
```

You can override the configuration file location using the `configFile` property in your `pom.xml`:

```xml
<configuration>
    <configFile>${project.basedir}/my-sanshain-config.yaml</configFile>
</configuration>
```

## Global Settings

The following settings can be specified in `sanshain.yaml`, overridden via Maven properties, or set as environment variables.

| Setting                     | `sanshain.yaml` | Maven Property           | Env Variable            | Default                                            |
|-----------------------------|-----------------|--------------------------|-------------------------|----------------------------------------------------|
| Sanshain URL                | `sanshainUrl`   | `-Dsanshain.url`         | `$SANSHAIN_URL`         | `http://localhost:8080`                            |
| Sanshain URL (settings.xml) | —               | —                        | —                       | via `<configuration><sanshainUrl>` in server entry |
| Token                       | —               | `-Dsanshain.token`       | `$SANSHAIN_TOKEN`       | — (optional)                                       |
| Timeout (seconds)           | `timeout`       | `-Dsanshain.timeout`     | `$SANSHAIN_TIMEOUT`     | `120`                                              |
| Compression                 | `compression`   | `-Dsanshain.compression` | `$SANSHAIN_COMPRESSION` | `true`                                             |
| Insecure                    | `insecure`      | `-Dsanshain.insecure`    | `$SANSHAIN_INSECURE`    | `false`                                            |
| Best Effort                 | `bestEffort`    | `-Dsanshain.bestEffort`  | `$SANSHAIN_BEST_EFFORT` | `false`                                            |
| Strict                      | `strict`        | `-Dsanshain.strict`      | —                       | `false`                                            |
| Service name                | `serviceName`   | `-Dsanshain.service.name`| `$SANSHAIN_SERVICE_NAME`| — (required)                                       |
| Branch                      | —               | `-Dsanshain.branch`      | `$SANSHAIN_BRANCH`      | auto-detected from Git                             |
| Dry-run                     | —               | `-Dsanshain.dry.run`     | —                       | `false`                                            |
| Force                       | —               | `-Dsanshain.force`       | `$SANSHAIN_FORCE`       | `false`                                            |
| Combine                     | `combine`       | `-Dsanshain.combine`     | `$SANSHAIN_COMBINE`     | `true`                                             |

## Goal: `provide` Parameters

| Parameter     | Property                | Default                                   | Description                                                                        |
|---------------|-------------------------|-------------------------------------------|------------------------------------------------------------------------------------|
| `serviceName` | `sanshain.service.name` | —                                         | **Required.** The name of the service providing the API.                           |
| `openApiFile` | `sanshain.openapi.file` | `${project.build.directory}/openapi.yaml` | Path to the default OpenAPI YAML file.                                             |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080`                   | URL of the Sanshain service.                                                       |
| `token`       | `sanshain.token`        | —                                         | Authentication token (prefer `settings.xml` or env variable).                      |
| `compression` | `sanshain.compression`  | `true`                                    | Enable gzip compression for the upload.                                            |
| `insecure`    | `sanshain.insecure`     | `false`                                   | Ignore SSL certificate errors.                                                     |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                                   | Don't fail the build on server errors.                                             |
| `serverId`    | `sanshain.serverId`     | `sanshain`                                | Server ID for `settings.xml` token lookup.                                         |
| `skip`        | `sanshain.skip`         | `false`                                   | Skip execution of all sanshain goals.                                              |
| `skipProvide` | `sanshain.provide.skip` | `false`                                   | Skip execution of the provide goal only.                                           |
| `dryRun`      | `sanshain.dry.run`      | `false`                                   | Validate without storing.                                                          |
| `force`       | `sanshain.force`        | `false`                                   | Force mode — override the existing contract. Also settable via `$SANSHAIN_FORCE`.  |
| `combine`     | `sanshain.combine`      | `true`                                    | Enable recursive local combining/bundling of multi-file specifications.            |
| `strict`      | `sanshain.strict`       | `false`                                   | Fail on missing config instead of warning.                                         |

## Goal: `require` Parameters

| Parameter     | Property                | Default                 | Description                                                                  |
|---------------|-------------------------|-------------------------|------------------------------------------------------------------------------|
| `serviceName` | `sanshain.service.name` | —                       | **Required.** The name of the client service requesting the endpoints.       |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080` | URL of the Sanshain service.                                                 |
| `token`       | `sanshain.token`        | —                       | Authentication token (prefer `settings.xml` or env variable).                |
| `timeout`     | `sanshain.timeout`      | `120`                   | Global timeout in seconds for server long-polling.                           |
| `compression` | `sanshain.compression`  | `true`                  | Enable gzip compression for downloads.                                       |
| `insecure`    | `sanshain.insecure`     | `false`                 | Ignore SSL certificate errors.                                               |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                 | Don't fail the build on server errors.                                       |
| `serverId`    | `sanshain.serverId`     | `sanshain`              | Server ID for `settings.xml` token lookup.                                   |
| `skip`        | `sanshain.skip`         | `false`                 | Skip execution of all sanshain goals.                                        |
| `skipRequire` | `sanshain.require.skip` | `false`                 | Skip execution of the require goal only.                                     |
| `dryRun`      | `sanshain.dry.run`      | `false`                 | Validate without recording dependencies.                                     |
| `strict`      | `sanshain.strict`       | `false`                 | Fail on missing config instead of warning.                                   |

## Advanced Modes

### Strict Mode
By default, the plugin warns and skips when configuration is incomplete. Enable strict mode to fail the build instead:
```bash
mvn verify -Dsanshain.strict=true
```

### Best-Effort Mode
Designed for non-critical builds or parent POMs. When enabled, server errors or timeouts are logged as warnings instead of failing the build.
```bash
mvn verify -Dsanshain.bestEffort=true
```

### Force Mode
Allows overriding existing contracts on feature branches. Fails on protected branches (e.g., `main`).
```bash
mvn verify -Dsanshain.force=true
```

### Dry-Run Mode
Validates requests against the service without persisting data or recording dependencies.
```bash
mvn verify -Dsanshain.dry.run=true
```

## Local Caching

The plugin uses a local cache at `target/.sanshain-cache.json` for:
- **Optimistic Concurrency**: Tracking `baseVersion` for conflict detection.
- **Content Caching**: Skipping uploads if the specification hasn't changed.
- **ETag Caching**: Avoiding file rewrites if the downloaded snippet is unchanged.
