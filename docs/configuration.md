# Configuration Reference

The Sanshain Maven Plugin is designed to be flexible, allowing configuration via `sanshain.yaml`, Maven properties, environment variables, or Maven `settings.xml`.

## `sanshain.yaml`

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
```

You can override the configuration file location using the `configFile` property in your `pom.xml`:

```xml
<configuration>
    <configFile>${project.basedir}/my-sanshain-config.yaml</configFile>
</configuration>
```

## Versions and stability

- The **version** of a provide is never configured — it lives in the spec file itself (`info.version` for OpenAPI/AsyncAPI, a mandatory `// sanshain-version:` comment for proto). Accepted spellings are `MAJOR[.MINOR[.PATCH]]` with an optional leading `v`; omitted parts are zero (`v2` becomes `2.0.0`) and the stored version is always the full three-part form. The same applies to a `version` pin in `requires`.
- Every `requires` entry pins an **exact version** (`version: MAJOR.MINOR.PATCH`). No ranges, no `latest`. A pin that does not exist on the server fails the require immediately (`404`).
- **Stability** is declared per build: the default is `snapshot`; `-Dsanshain.ga=true` or `SANSHAIN_GA=true` provides as `ga`. There is no stability field in `sanshain.yaml`.

## Streams: trunk and release branches

Alongside stability, a pipeline declares which **graph** its calls belong to. Like stability, this is
a property of the invocation and never appears in `sanshain.yaml` — the same checkout is built by
trunk CI and by a developer's laptop, and the trunk pin store is last-writer-wins, so a guess would
let a local build overwrite CI. Nothing is inferred from the git branch.

- **Trunk CI** — the build of your default branch — sets `-Dsanshain.trunk=true` or
  `SANSHAIN_TRUNK=true`. Its provides mark trunk's current version and its requires become the main
  graph's pins. Nightly re-runs of an unchanged build are wanted: they keep trunk data fresh instead
  of ageing out.
- **Release and hotfix pipelines** set `-Dsanshain.tag=<branch>` or `SANSHAIN_TAG=<branch>`. The
  calls update that release's graph instead of trunk. The branch must already exist — a releaser
  creates it at cut time — and an unknown one fails the build with a `404`.

Declaring both fails the build before any request is made. Streams never change what is served or
any version rule; they only decide which graph records the call.

## Retiring a protocol

Deleting a `provides` entry says nothing: Sanshain cannot tell a dropped protocol from a pipeline
that merely stopped running, so the capability tag, graph edges and channel contracts would linger.
Keep the entry and mark it retired:

```yaml
provides:
  - file: src/main/resources/asyncapi.yaml
    apiType: asyncapi
    retired: true
```

The plugin sends that family's provide call with `retired: true` and no document. Version history and
existing Consumer pins are untouched — only the capability is withdrawn.

Retiring is a role, like releasing: the build's token needs `releaser` (which it already holds if it
publishes GA) or a maintainer grant on this Producer, or the server answers `403`. Under
`-Dsanshain.dry.run=true` the call checks permission and retires nothing.

The configuration is validated at parse time, before any network call. Leftover 1.x fields (`branch`, `timeout`, `baseVersion`, `releaseBranches`, `stability` in the YAML) and `requires` entries without a `version` pin are rejected by name with a migration hint.

## Global Settings

The following settings can be specified in `sanshain.yaml`, overridden via Maven properties, or set as environment variables.

| Setting                     | `sanshain.yaml` | Maven Property           | Env Variable            | Default                                            |
|-----------------------------|-----------------|--------------------------|-------------------------|----------------------------------------------------|
| Sanshain URL                | `sanshainUrl`   | `-Dsanshain.url`         | `$SANSHAIN_URL`         | `http://localhost:8080`                            |
| Sanshain URL (settings.xml) | —               | —                        | —                       | via `<configuration><sanshainUrl>` in server entry |
| Token                       | —               | `-Dsanshain.token`       | `$SANSHAIN_TOKEN`       | — (optional)                                       |
| GA switch (provide only)    | —               | `-Dsanshain.ga`          | `$SANSHAIN_GA`          | `false` (provide as `snapshot`)                    |
| Trunk stream                | —               | `-Dsanshain.trunk`       | `$SANSHAIN_TRUNK`       | `false`                                            |
| Branch stream               | —               | `-Dsanshain.tag`         | `$SANSHAIN_TAG`         | — (no branch)                                      |
| Compression                 | `compression`   | `-Dsanshain.compression` | `$SANSHAIN_COMPRESSION` | `true`                                             |
| Insecure                    | `insecure`      | `-Dsanshain.insecure`    | `$SANSHAIN_INSECURE`    | `false`                                            |
| Best Effort                 | `bestEffort`    | `-Dsanshain.bestEffort`  | `$SANSHAIN_BEST_EFFORT` | `false`                                            |
| Strict                      | `strict`        | `-Dsanshain.strict`      | —                       | `false`                                            |
| Service name                | `serviceName`   | `-Dsanshain.service.name`| `$SANSHAIN_SERVICE_NAME`| — (required)                                       |
| Dry-run                     | —               | `-Dsanshain.dry.run`     | —                       | `false`                                            |
| Combine                     | `combine`       | `-Dsanshain.combine`     | `$SANSHAIN_COMBINE`     | `true`                                             |

## Goal: `provide` Parameters

| Parameter     | Property                | Default                                   | Description                                                                        |
|---------------|-------------------------|-------------------------------------------|------------------------------------------------------------------------------------|
| `serviceName` | `sanshain.service.name` | —                                         | **Required.** The name of the service providing the API.                           |
| `openApiFile` | `sanshain.openapi.file` | `${project.build.directory}/openapi.yaml` | Path to the default OpenAPI YAML file.                                             |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080`                   | URL of the Sanshain service.                                                       |
| `token`       | `sanshain.token`        | —                                         | Authentication token (prefer `settings.xml` or env variable).                      |
| `ga`          | `sanshain.ga`           | `false`                                   | Provide as `ga` instead of `snapshot`. Also settable via `$SANSHAIN_GA`.           |
| `trunk`       | `sanshain.trunk`        | `false`                                   | This build is trunk's. Also settable via `$SANSHAIN_TRUNK`.                        |
| `tag`         | `sanshain.tag`          | —                                         | This build is a sanshain-branch's. Also settable via `$SANSHAIN_TAG`.              |
| `compression` | `sanshain.compression`  | `true`                                    | Enable gzip compression for the upload.                                            |
| `insecure`    | `sanshain.insecure`     | `false`                                   | Ignore SSL certificate errors.                                                     |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                                   | Don't fail the build on server errors.                                             |
| `serverId`    | `sanshain.serverId`     | `sanshain`                                | Server ID for `settings.xml` token lookup.                                         |
| `skip`        | `sanshain.skip`         | `false`                                   | Skip execution of all sanshain goals.                                              |
| `skipProvide` | `sanshain.provide.skip` | `false`                                   | Skip execution of the provide goal only.                                           |
| `dryRun`      | `sanshain.dry.run`      | `false`                                   | Validate without storing.                                                          |
| `combine`     | `sanshain.combine`      | `true`                                    | Enable recursive local combining/bundling of multi-file specifications.            |
| `strict`      | `sanshain.strict`       | `false`                                   | Fail on missing config instead of warning.                                         |

## Goal: `require` Parameters

| Parameter     | Property                | Default                 | Description                                                                  |
|---------------|-------------------------|-------------------------|------------------------------------------------------------------------------|
| `serviceName` | `sanshain.service.name` | —                       | **Required.** The name of the client service requesting the endpoints.       |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080` | URL of the Sanshain service.                                                 |
| `token`       | `sanshain.token`        | —                       | Authentication token (prefer `settings.xml` or env variable).                |
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

### Dry-Run Mode
Validates requests against the service without persisting data or recording dependencies.
```bash
mvn verify -Dsanshain.dry.run=true
```

## Local Caching

The plugin uses a local cache at `target/.sanshain-cache.json` for:
- **Content Caching**: Skipping uploads if the specification hasn't changed (SHA-256 hash comparison).
- **ETag Caching**: Avoiding file rewrites if the downloaded snippet is unchanged (`304 Not Modified`).

The cache is deleted by `mvn clean`, which forces a full re-synchronization with the server.
