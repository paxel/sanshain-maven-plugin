# Sanshain Maven Plugin

The Sanshain Maven Plugin allows microservices to interact with the Sanshain service to manage and distribute API specifications (OpenAPI, AsyncAPI, gRPC/Proto) during the build process. A **Producer** provides its complete specification under a producer-declared **version**; a **Consumer** pins an exact version and requires only the endpoint snippets it uses.

## Goals

- `sanshain:provide`: Uploads a full API specification to the Sanshain service under its declared version and stability. Defaults to the `initialize` phase.
- `sanshain:require`: Downloads specific endpoint snippets at exact pinned versions. Defaults to the `initialize` phase.
- `sanshain:catalog`: Pushes this service's Backstage `catalog-info.yaml` (the `catalogInfo` field) so the network graph shows the peripheral resources it depends on. Defaults to the `initialize` phase. Needs Sanshain Service 2.4.0 or later.

## Requirements

- **Sanshain service 2.x.** Plugin 2.x speaks Sanshain Service 2.x — this is a clean break from the 1.x branch-based contract; neither side accepts the other's old fields. The `catalog` goal needs Sanshain Service 2.4.0 or later; all other goals work with any 2.x service.
- Java 11 or later.

## The 2.0 model in one minute

- **The version lives in the spec file.** For OpenAPI and AsyncAPI it is read from `info.version`; for proto from a mandatory `// sanshain-version:` comment. Accepted spellings are `MAJOR[.MINOR[.PATCH]]` with an optional leading `v` — omitted parts are zero (`v2` becomes `2.0.0`) and the stored version is always the full three-part form. No `-SNAPSHOT` or other suffixes. The plugin never sends a version on provide; the server reads it from the document.
- **Stability is declared on every provide**: `snapshot` (work-in-progress, overwritable by the user who last provided it, expires when unused) or `ga` (immutable; the number is permanently claimed). The default is always `snapshot`; GA is an explicit switch (`-Dsanshain.ga=true` or `SANSHAIN_GA=true`) that CI sets on release pipelines.
- **Consumers pin exact versions.** Every `requires` entry carries a `version`. Resolution prefers GA, falls back only to the same-numbered snapshot, and otherwise fails immediately — no fallback to other versions, no waiting.
- **Branches are gone.** There is no branch detection, no branch parameter, no long-polling, no force mode, and no `baseVersion` optimistic concurrency. GA immutability is the concurrency control.

## Quick Start

Add the plugin to your `pom.xml`:

```xml
<plugin>
    <groupId>io.github.paxel.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>2.4.0</version>
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
catalogInfo: catalog-info.yaml   # optional, pushed by sanshain:catalog

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

Every relative path in `sanshain.yaml` (`file`, `openApiFile`, `asyncApiFile`, `protoFile`,
`outputDirectory`, `catalogInfo`) resolves against the directory that contains `sanshain.yaml`, never the working
directory. With the default location that is the project base directory, so nothing changes; a
`configFile` in another folder finds its spec files and output directories next to itself. Absolute
paths stay absolute. The default output directory (`target/generated-sources/sanshain`) stays under
the project's base directory.

> **Note:** The `token` is intentionally **not** stored in `sanshain.yaml`. See [Authentication](#authentication) below.

### Migrating a 1.x `sanshain.yaml`

The plugin validates the configuration at parse time, before any network call, and rejects every leftover 1.x field **by name** with a migration hint:

- A `requires` entry without `version` → add an exact pin, e.g. `version: 1.2.0` (list what's available with `GET /producers/<name>/versions`).
- `branch` (anywhere) → the branch model was removed in Sanshain 2.0; replace with an exact `version` pin.
- `timeout` (global or per-require) → resolution is immediate; there is no long-polling. Remove it.
- `baseVersion` → GA immutability replaced optimistic concurrency. Remove it.
- `releaseBranches` / `stability` in the YAML → stability is declared per build via the [GA switch](#stability-the-ga-switch), not configured in the file.

### Unknown fields

A field in `sanshain.yaml` that no Sanshain client knows (a typo such as `catalogInf` or
`requires[0].outputDirectroy`) does not fail the build. The plugin names it once per goal and
carries on:

```
   /\_/\  ?
  ( O_o )   sanshain.yaml: unknown field 'catalogInf' - ignored
 ---U-U---
```

In strict mode (`-Dsanshain.strict=true` or `strict: true` in `sanshain.yaml`) the same cat says `rejected (strict)` and the goal fails. Best-effort
does not soften this, because it is a configuration error, not a failed call. Fields other Sanshain
clients know but this plugin does not use (for example `force`) are accepted silently. The removed
1.x fields listed above still fail by name first.

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
| Build URL (provide only)    | —               | `-Dsanshain.buildUrl`     | `$SANSHAIN_BUILD_URL`    | the runner's own variable (see [Origin](#origin-why-this-build-provides)) |
| Change URL (provide only)   | —               | `-Dsanshain.changeUrl`    | `$SANSHAIN_CHANGE_URL`   | the runner's own variable (see [Origin](#origin-why-this-build-provides)) |
| Tickets (provide only)      | —               | `-Dsanshain.tickets`      | `$SANSHAIN_TICKETS`      | — (comma-separated ids) |
| Note (provide only)         | —               | `-Dsanshain.note`         | `$SANSHAIN_NOTE`         | — (one line, at most 200 characters) |

Boolean environment switches (`$SANSHAIN_GA`, `$SANSHAIN_TRUNK`, `$SANSHAIN_BEST_EFFORT`,
`$SANSHAIN_COMPRESSION`, `$SANSHAIN_INSECURE`, `$SANSHAIN_COMBINE`) are on when the value, trimmed and
in any case, is `true` or `1`; anything else is off. An explicit Maven property still overrides the
environment.

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
| `strict`      | `sanshain.strict`       | `false`                                   | Fail on missing config, or when Sanshain sends a warning, instead of warning (see [Strict Mode](#strict-mode)). |

These parameters can also be provided via the `provides` list in `sanshain.yaml`:

```yaml
serviceName: my-service
provides:
  - file: src/main/resources/openapi.yaml
    apiType: openapi
  - file: src/main/resources/events.yaml
    apiType: asyncapi
```

> ⚠️ **AsyncAPI 2.x perspective.** Sanshain reads 2.x `publish`/`subscribe` from the
> **application's** perspective: `publish` means *this service publishes to the channel*,
> `subscribe` means *this service consumes it*. The AsyncAPI 2.x specification defines those
> keywords from the **client's** perspective — exactly inverted. Sanshain uses the
> application-perspective reading deliberately, because it matches the unambiguous 3.x
> `send`/`receive` mapping. A document authored with the spec-literal reading registers its
> contracts, and has its subscriptions harvested, exactly backwards.

Every `subscribe` operation is harvested as a consumer edge and checked against the publishing
Producer's contract. The plugin prints each one; those with no publisher yet, or expecting a field
the contract does not guarantee, are printed as build warnings. They never fail the build — a GA
provide whose expectation is unsatisfiable is refused by the server with `409`, so the warning on
the snapshot build is your chance to fix it first.

### Streams: trunk and release branches

A pipeline declares which graph its calls belong to, the same way it declares stability — never in
`sanshain.yaml`, because the same checkout is built by trunk CI and by a developer's laptop:

- Trunk CI sets `-Dsanshain.trunk=true` or `SANSHAIN_TRUNK=true`.
- Release and hotfix pipelines set `-Dsanshain.tag=<branch>` or `SANSHAIN_TAG=<branch>`. The branch
  must already exist; for an unknown one Sanshain still serves the call but records the build in no
  release graph, and says so with a [warning](#warnings).

Declaring both records the build in neither graph: the goal warns
`trunk and tag are mutually exclusive - this build is recorded in neither` and sends neither. Under
[strict mode](#strict-mode) it fails before any request is sent.

### Origin: why this build provides

A provide may carry what the pipeline knows about *why* it runs (Sanshain Service 2.9.0 and later).
Sanshain stores it with the version as its origin, shows it on the version row, in the diff and the
blame, and answers it to assistants over MCP:

- **Build URL** and **change URL** (the pull or merge request) are taken from the runner's own
  variables when present — GitHub Actions (`GITHUB_SERVER_URL`, `GITHUB_REPOSITORY`,
  `GITHUB_RUN_ID`, a pull request's `GITHUB_REF`), GitLab CI (`CI_JOB_URL`,
  `CI_MERGE_REQUEST_PROJECT_URL`, `CI_MERGE_REQUEST_IID`) and Jenkins (`BUILD_URL`, `CHANGE_URL`) —
  so every pipeline carries them without editing a job. `-Dsanshain.buildUrl` /
  `SANSHAIN_BUILD_URL` and `-Dsanshain.changeUrl` / `SANSHAIN_CHANGE_URL` override them.
- **Tickets** (`-Dsanshain.tickets` / `SANSHAIN_TICKETS`, comma-separated ids) and the **note**
  (`-Dsanshain.note` / `SANSHAIN_NOTE`, one line) are human intent and come only from the pipeline
  that knows them. Sanshain links ticket ids through the ticket URL template an administrator set.

The latest provide that carries any of it wins; a byte-identical re-provide only fills an empty
origin, so CI rebuilding what a developer provided from a laptop adds the references without erasing
them. Nothing is read from git. A service before 2.9.0 refuses the origin fields; the plugin then
warns and repeats the provide without them, so upgrading the plugin first never fails a build.

### Retiring a protocol

Removing a `provides` entry does not tell Sanshain anything — it cannot distinguish a dropped
protocol from a pipeline that stopped running. Keep the entry and mark it:

```yaml
provides:
  - file: src/main/resources/events.yaml
    apiType: asyncapi
    retired: true
```

The capability tag, the family's place in the current graph and its channel contracts are released;
version history and existing Consumer pins are untouched. Retiring needs the `releaser` role — which
a release pipeline already holds — or a maintainer grant on this Producer.

### Version rules and `409` rejections

The server enforces the version rules at the door, and every rejection is self-service:

- **Idempotency**: re-providing byte-identical content is a no-op (`changes` all zero) — CI re-runs of the same commit never fight.
- **Snapshots** are overwritable in place by the user who last provided them (Sanshain Service 2.9.0 and later). Identical bytes from anyone, such as CI rebuilding a commit the developer already provided, are a no-op; different content from another user is rejected `409` (`snapshot_owned`) naming that user, with `proposed_version` the next free minor, so two developers who picked the same number cannot break each other's Consumer builds. An administrator deletes the snapshot to free the number. **GA is immutable**. A provide against an existing GA version with different content is rejected `409` — the response carries `proposed_version`, the next free number bumped by what actually changed (breaking → major, additive → minor, shape-identical → patch).
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
| `strict`      | `sanshain.strict`       | `false`                 | Fail on missing config, or when Sanshain sends a warning, instead of warning (see [Strict Mode](#strict-mode)). |

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

### `requires` entry fields

| Field             | Required | Default                              | Description |
|-------------------|----------|--------------------------------------|-------------|
| `serviceName`     | yes      | —                                    | The Producer to require from. |
| `version`         | yes      | —                                    | Exact pinned version (`MAJOR.MINOR.PATCH`). |
| `apiType`         | no       | `openapi`                            | `openapi`, `asyncapi` or `proto`. |
| `format`          | no       | — (native format)                    | Only `openapi`, and only with `apiType: asyncapi`: receive the AsyncAPI endpoints as an OpenAPI document. Needs Sanshain Service 2.4.0 or later. |
| `outputDirectory` | no       | `target/generated-sources/sanshain`  | Where the snippet or bundle is written (relative to `sanshain.yaml`). |
| `endpoints`       | yes      | —                                    | `method` + `path` pairs to require. |

### AsyncAPI as OpenAPI (`format: openapi`)

`format: openapi` on an `apiType: asyncapi` entry asks Sanshain to answer the required AsyncAPI
endpoints as an OpenAPI document, so a consumer can generate its DTOs with OpenAPI tooling. It is the
only conversion there is: `format` with any other `apiType`, or any other value, fails the build before
any call. It needs **Sanshain Service 2.4.0 or later**. An older service rejects the single-endpoint
request or returns AsyncAPI; the client reports that it needs 2.4.0:

```
format: openapi needs Sanshain Service 2.4.0 or later (server is 2.3.1)
```

The file name does not change (`{serviceName}_bundle.yaml` or `{serviceName}_{path}_{method}.yaml`).
To get both formats, list the entry twice with different `outputDirectory`s:

```yaml
requires:
  - serviceName: order-events
    apiType: asyncapi
    version: 1.4.0
    outputDirectory: target/generated-sources/sanshain/order-events
    endpoints:
      - method: SUB
        path: orders
      - method: SUB
        path: payments
  - serviceName: order-events
    apiType: asyncapi
    format: openapi
    version: 1.4.0
    outputDirectory: target/generated-sources/sanshain/order-events-openapi
    endpoints:
      - method: SUB
        path: orders
      - method: SUB
        path: payments
```

A consumer pom that requires the OpenAPI form in `initialize` and generates Spring models from it:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.paxel.sanshain</groupId>
            <artifactId>sanshain-maven-plugin</artifactId>
            <version>2.4.0</version>
            <executions>
                <execution>
                    <phase>initialize</phase>
                    <goals>
                        <goal>require</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
        <plugin>
            <groupId>org.openapitools</groupId>
            <artifactId>openapi-generator-maven-plugin</artifactId>
            <version>7.8.0</version>
            <executions>
                <execution>
                    <phase>generate-sources</phase>
                    <goals>
                        <goal>generate</goal>
                    </goals>
                    <configuration>
                        <inputSpec>${project.build.directory}/generated-sources/sanshain/order-events-openapi/order-events_bundle.yaml</inputSpec>
                        <generatorName>spring</generatorName>
                        <generateApis>false</generateApis>
                        <output>${project.build.directory}/generated-sources/openapi</output>
                        <modelPackage>com.example.orders.events.model</modelPackage>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```


### Failure modes

The plugin surfaces the two failure modes distinctly:

- **`404` Unknown** — the Producer, or the pinned version, does not exist in either stability. This is a configuration error and fails in milliseconds: fix the `version` pin (list what's available with `GET /producers/<name>/versions`).
- **`410` Absent** — the pinned version exists and deliberately does not include the requested endpoint(s). Provided specs are complete, so absence is a definitive no. For bundles, the whole bundle fails and the body names the missing endpoints.

If a provide or require fails against a server that turns out to be pre-2.0, the plugin checks the instance version once (`GET /version`) and replaces the confusing wire error with a clear message:

```
Sanshain server at https://sanshain.example.com is 1.7.0; this client requires Sanshain 2.x — upgrade the server.
```

## Goal: `catalog`

Pushes this service's Backstage `catalog-info.yaml` to Sanshain (`PUT /catalog/<serviceName>`), so the
network graph can show the **peripheral resources** it depends on — databases, buckets, queues. Sanshain
reads only the `Component` whose `metadata.name` equals `serviceName`, and of it only `spec.dependsOn`;
only `resource:` refs become graph nodes. Each push replaces the previous set.

Needs **Sanshain Service 2.4.0 or later**. An older service answers `404`, which the goal reports as:

```
sanshain catalog needs Sanshain Service 2.4.0 or later (server is 2.3.1)
```

Point `catalogInfo` in `sanshain.yaml` at the file (relative to `sanshain.yaml`):

```yaml
serviceName: orders-service
catalogInfo: catalog-info.yaml
```

Run it as its own goal, or add it to an execution next to `provide`/`require`:

```bash
mvn sanshain:catalog
```

```xml
<goals>
    <goal>provide</goal>
    <goal>require</goal>
    <goal>catalog</goal>
</goals>
```

On success it prints the recorded resources:

```
Catalog recorded for orders-service (main+dev): 1 resources
  resource:default/orders-db
```

`main+dev` means a trunk build (`-Dsanshain.trunk=true` / `SANSHAIN_TRUNK=true`), which sends
`"trunk": true` and updates the main graph as well as the dev graph; any other build updates the dev
graph only (`dev`). A sanshain-branch tag is never sent on this call.

### Parameters

| Parameter     | Property                | Default                 | Description |
|---------------|-------------------------|-------------------------|-------------|
| `serviceName` | `sanshain.service.name` | —                       | **Required.** The system the file describes. |
| `sanshainUrl` | `sanshain.url`          | `http://localhost:8080` | URL of the Sanshain service. |
| `token`       | `sanshain.token`        | —                       | Authentication token (prefer `settings.xml` or env variable). |
| `compression` | `sanshain.compression`  | `true`                  | Enable gzip compression for the upload. |
| `insecure`    | `sanshain.insecure`     | `false`                 | Ignore SSL certificate errors. |
| `bestEffort`  | `sanshain.bestEffort`   | `false`                 | Turn a failed push into a warning (see [Best-Effort Mode](#best-effort-mode)). |
| `trunk`       | `sanshain.trunk`        | `false`                 | This build is trunk's. Also settable via `$SANSHAIN_TRUNK`. |
| `serverId`    | `sanshain.serverId`     | `sanshain`              | Server ID for `settings.xml` token lookup. |
| `skip`        | `sanshain.skip`         | `false`                 | Skip execution of all sanshain goals. |
| `dryRun`      | `sanshain.dry.run`      | `false`                 | Read and check the file locally, push nothing (see [Dry-Run Mode](#dry-run-mode)). |
| `strict`      | `sanshain.strict`       | `false`                 | Fail when `serviceName` or `catalogInfo` is missing, or when Sanshain sends a warning. |

### Failure modes

- **No `catalogInfo`** — warns `No catalogInfo in sanshain.yaml - skipping catalog.` and succeeds;
  under strict it fails with `strict: sanshain.yaml has no catalogInfo`. Of the configuration,
  strict checks only `serviceName` and `catalogInfo` for this goal, never `provides` or `requires`.
- **File missing or unreadable** — fails naming the resolved path.
- **File Sanshain cannot read** — invalid YAML, no `Component` whose `metadata.name` is `serviceName`,
  a malformed `dependsOn`: Sanshain records nothing (the previous periphery stays) and answers with a
  [warning](#warnings) naming the reason, which the goal prints before succeeding. Under strict it
  fails with `strict: Sanshain warned: <reason>`.
- **`404`** — the service is older than 2.4.0 (see above).
- Everything else (`401`, `403`, `413`, `422`, `5xx`, network) fails like the other goals.

With best-effort, every failure after the `catalogInfo` check becomes a warning and the build goes on,
except the strict failures.

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
    <version>2.4.0</version>
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

### Provides are always uploaded

Every `provide` uploads its spec; nothing is cached on the client. Re-providing byte-identical
content is a no-op on the server, so CI re-runs of the same commit are harmless.

### Require-Side ETag Caching

The plugin stores the `ETag` header from require responses. On subsequent builds, it sends `If-None-Match` with the stored ETag. If the server responds with `304 Not Modified`, the output file is not rewritten:

```
⏭ user-service spec unchanged (304), skipping code generation.
```

This avoids unnecessary code generation and speeds up incremental builds. A pin on a GA version can never change content; a pin on a snapshot can, which is exactly what the ETag detects.

### State File

The local cache is stored at `target/.sanshain-cache.json` and is automatically deleted by `mvn clean`. It holds only require ETags; a `provides` section written by an older plugin version is ignored. The format is:

```json
{
  "requires": {
    "user-service|2.3.0|GET|/api/v1/users": {
      "etag": "\"sha256:def456...\"",
      "last_fetched": "2026-04-25T12:00:00Z"
    }
  }
}
```

## Warnings

The dependency graph is informational and never fails a build. Where Sanshain does not record a call
in a graph — an unknown `sanshain.tag`, a `catalog-info.yaml` it cannot read — the call still
succeeds, and the response carries a `Sanshain-Warning` header, one line per warning. Every goal
prints each one as a Maven warning, as received, from every call (`provide`, retire, `require`,
`catalog`), whether the call succeeded or not:

```
[WARNING] no sanshain-branch 'release-9' - this build was not recorded in a release graph
```

The build carries on. Under [strict mode](#strict-mode) a warning fails the goal after the call,
with `strict: Sanshain warned: <text>` naming the first one; best-effort does not soften that.

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

Or in `sanshain.yaml`:

```yaml
strict: true
```

An explicitly set `sanshain.strict` property (`true` or `false`) wins over the `sanshain.yaml` value;
when the property is not set, `sanshain.yaml` decides; otherwise strict mode is off.

When strict mode is enabled:
- Missing `serviceName` → build failure
- No `provides` configured → build failure
- No `requires` configured → build failure
- Unknown fields in `sanshain.yaml` → build failure (see [Unknown fields](#unknown-fields))
- Any [warning](#warnings) Sanshain sends → build failure after the call (`strict: Sanshain warned: <text>`)
- Both trunk and a tag declared → build failure before any request
  (`strict: trunk and tag are mutually exclusive - this build is recorded in neither`)

Best-effort never softens these.

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
            <version>2.4.0</version>
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
            <version>2.4.0</version>
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
- **`require`**: Endpoint lookups are performed at the pinned versions, but no dependencies are recorded, and no ETag is written to the local cache.
- **`catalog`**: The `catalogInfo` file is read and checked locally, and the goal logs `Dry run - catalog not pushed: <path>` instead of pushing; nothing is sent.

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

To push the service's `catalog-info.yaml` (needs Sanshain Service 2.4.0), add the catalog goal as a
step; trunk CI sets the trunk switch so the main graph is updated too:

```bash
export SANSHAIN_TRUNK=true                # trunk pipeline only
mvn sanshain:catalog
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
