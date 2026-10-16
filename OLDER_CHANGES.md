# Older Changes

This file contains historical changelog entries for the Sanshain Maven Plugin.
For recent changes, see [CHANGELOG.md](CHANGELOG.md).

## [2.2.0] - 2026-10-16

### Added
- `sanshain:catalog` goal: pushes the service's Backstage `catalog-info.yaml`, named by the new `catalogInfo` field in `sanshain.yaml`, so the network graph shows the peripheral resources it depends on; a trunk build updates the main graph as well as the dev graph, and `-Dsanshain.dry.run=true` only checks the file locally. A file Sanshain cannot read records nothing and is printed as a warning; the goal succeeds (fails under strict mode).
- `catalog` requires Sanshain Service 2.4.0; all other commands work with any 2.x service.
- `format: openapi` on an `apiType: asyncapi` require entry receives the endpoints as an OpenAPI document (needs Sanshain Service 2.4.0).
- `strict: true` in `sanshain.yaml` turns on strict mode; an explicitly set `-Dsanshain.strict` (true or false) overrides it.

### Changed
- Relative paths in `sanshain.yaml` (`file`, `openApiFile`, `asyncApiFile`, `protoFile`, `outputDirectory`, `catalogInfo`) resolve against the directory containing `sanshain.yaml` instead of the project base directory; with the default `configFile` nothing moves.
- Unknown fields in `sanshain.yaml` no longer fail the build: a warning names them and they are ignored, unless `sanshain.strict` is on, which fails the goal.
- `provide` always uploads; the client-side content-hash cache that skipped unchanged specs is gone (an identical re-provide is a no-op on the server).
- `SANSHAIN_*` boolean environment variables are on for `true` or `1` in any case and with surrounding whitespace; any other value is off.
- Declaring both trunk and a tag (`sanshain.trunk`/`SANSHAIN_TRUNK` and `sanshain.tag`/`SANSHAIN_TAG`) no longer fails: the goal warns `trunk and tag are mutually exclusive - this build is recorded in neither` and sends neither; strict mode fails instead, before any request.
- Every warning Sanshain sends with a response (the `Sanshain-Warning` header, e.g. for an unknown sanshain-branch, which no longer fails the call) is printed as a Maven warning, one line per warning; under strict mode it fails the goal with `strict: Sanshain warned: <text>`, even with best-effort.

### Fixed
- A dry-run `provide` no longer makes the following real `provide` log "Spec unchanged" and publish nothing.
- A dry-run `require` no longer writes ETags to the local cache.
- Absolute paths in `sanshain.yaml` are used as given instead of being appended to the project directory.

## [2.1.0] - 2026-08-14

### Added
- `sanshain.trunk` property and `SANSHAIN_TRUNK` env var mark a build as trunk CI, so its provides and requires are recorded in the trunk graph.
- `sanshain.tag` property and `SANSHAIN_TAG` env var mark a build as belonging to a release or hotfix branch, so its calls are recorded in that branch's graph.
- The build fails before any request is sent if it declares both trunk and a tag.
- `retired: true` on a `provides` entry retires that API family on the server instead of uploading a document, and logs a summary of what was released.
- With dry-run, a retired entry only checks whether retiring is permitted.
- Provide logs every AsyncAPI subscription the server harvested, and logs a warning for any subscription with no publisher yet or with contract drift.

### Changed
- Spec content is sent with LF line endings, even when the local file uses CRLF.
- A `403` on provide fails with a message saying that publishing GA needs the `releaser` role, or that you can publish as a snapshot instead.

## [2.0.0] - 2026-08-03

### Added
- `sanshain.ga` property and `SANSHAIN_GA` env var publish a provide as immutable `ga`; without them, every provide is a `snapshot`.
- Required `version` field on each `requires` entry, which pins the exact producer version to download.
- `sanshain.yaml` is validated before any network call, and each leftover 1.x field is rejected by name with a migration hint.
- A `409` on provide prints the server's proposed version and tells you which version to set in the spec file.
- When a call fails against a pre-2.0 server, the build fails with a message giving the server version and asking for an upgrade.
- A `422` on provide fails with a message that the server rejected the request's fields.

### Changed
- Requires Sanshain Service 2.x, because the plugin no longer speaks the 1.x branch-based protocol.
- The provide version is read by the server from the spec file (`info.version`, or the `// sanshain-version:` comment for proto) rather than sent by the plugin.
- Require resolves the pinned version immediately and uses a fixed 30-second request timeout instead of long-polling.
- A `404` on require now means an unknown producer or version, and the build fails with a hint to fix the `version` pin.
- A `410` on require now means the pinned version exists but deliberately leaves out the requested endpoint.
- Provide success logs the stored version and stability, for example `✓ Provided to Sanshain as 1.4.0 (ga)`.
- Require logs `producer@version` and shows whether the downloaded content came from `ga` or `snapshot`.
- A `400` on provide fails with a hint that the spec's version is most likely missing or not valid semver.
- The failure cat art is larger.

### Removed
- Branch support: the `sanshain.branch` property, the `SANSHAIN_BRANCH` env var, the `branch` field in `sanshain.yaml`, and Git or CI branch detection.
- The `sanshain.sourceProtectedBranch` property and `SANSHAIN_SOURCE_PROTECTED_BRANCH` env var, including their auto-detection.
- The `sanshain.pullFromBranch` property and `SANSHAIN_PULL_FROM_BRANCH` env var.
- Long-polling: the `sanshain.timeout` property, the `SANSHAIN_TIMEOUT` env var, and the global and per-require `timeout` fields.
- Force mode: the `sanshain.force` property and `SANSHAIN_FORCE` env var.
- The `baseVersion` field and automatic optimistic-concurrency tracking.
- The `sanshain.author` property, because the authenticated token identity is now used for attribution.
- The log line about inheriting a spec from another branch.

## [1.12.0] - 2026-07-28

### Added
- `sanshain.author` property overrides who is credited in version-history blame for a provide.
- `sanshain.sourceProtectedBranch` property and `SANSHAIN_SOURCE_PROTECTED_BRANCH` env var send the protected branch that this branch falls back to.
- When `sourceProtectedBranch` is not set, it is auto-detected from CI pull-request base-branch variables or a Git merge-base against the server's protected branch patterns.
- `sanshain.pullFromBranch` property and `SANSHAIN_PULL_FROM_BRANCH` env var make a single build resolve its requires against one exact branch.
- A `410 Gone` on require fails the build immediately, with a message that the endpoint is deliberately not published on that branch.
- Require logs at INFO when a spec was inherited from another branch.

### Changed
- Requests use the `producername`/`consumername` fields, so Sanshain service 1.6.0 or later is required.

## [1.11.1] - 2026-07-21

### Added
- Failed provide and require calls print cat ASCII art before the error.

### Changed
- The `require` goal now runs in the `initialize` phase by default instead of `generate-sources`.

## [1.9.0] - 2026-06-01

### Added
- Multi-file specs are combined before upload: local `$ref`s in OpenAPI and AsyncAPI files and local `import`s in proto files are resolved and inlined.
- The `combine` setting turns combining on or off and is enabled by default.
- `combine` can be set with the `sanshain.combine` property, the `SANSHAIN_COMBINE` env var, the global `combine` field, or a per-entry `combine` field in `sanshain.yaml`.
- Combining fails the build with the full reference path when it finds a circular reference.

## [1.8.0] - 2026-05-11

### Added
- `bestEffort` plugin parameter (`-Dsanshain.bestEffort`) for both `provide` and `require`, which takes precedence over `SANSHAIN_BEST_EFFORT` and the `bestEffort` field in `sanshain.yaml`.

### Changed
- In best-effort mode, an unreadable or invalid `sanshain.yaml` is logged as a warning and the build continues.
- In best-effort mode, errors writing required spec files are logged as warnings and the build continues.
- In best-effort mode, missing configuration (no `serviceName`, no `provides`, no `requires`) is logged as a warning even when `sanshain.strict=true`.
- Best-effort warnings now read "Sanshain goal failed (best effort)" or "Sanshain goal skipped (best effort)".

## [1.7.0] - 2026-05-11

### Added
- `force` parameter (`-Dsanshain.force`, `SANSHAIN_FORCE`) for `provide`, which sends `force` to the Sanshain service to replace the existing contract on non-protected branches.
- `provide` logs a notice when force mode is enabled.

## [1.6.1] - 2026-04-26

### Added
- The Sanshain URL can be set as `<url>` in the `settings.xml` server configuration, in addition to `<sanshainUrl>`.

### Changed
- The plugin is now licensed under Apache License 2.0 instead of AGPL-3.0.
- Branch detection order is now `-Dsanshain.branch`, `SANSHAIN_BRANCH`, CI variables, the local Git branch, and then `git rev-parse --abbrev-ref HEAD`.
- The CI variables used for branch detection are now `GITHUB_REF_NAME`, `GIT_BRANCH`, `CI_COMMIT_REF_NAME`, `BITBUCKET_BRANCH` and `BRANCH_NAME`.
- When no branch can be detected, the plugin logs a warning and no longer falls back to `main`.
- `require` progress lines no longer show the timeout or the API type.
- Best-effort failures are now reported with one warning per goal or per required service.

### Removed
- `GITHUB_HEAD_REF`, `CI_COMMIT_BRANCH`, `CI_MERGE_REQUEST_SOURCE_BRANCH_NAME`, `BUILD_SOURCEBRANCH`, `TRAVIS_BRANCH` and `CIRCLE_BRANCH` are no longer used for branch detection.
- `GIT_BRANCH` values no longer have an `origin/` prefix stripped.
- A detached HEAD is no longer resolved to a branch by matching local or remote refs.

## [1.5.0] - 2026-04-25

### Added
- `baseVersion` field on `provides` entries, sent as `base_version` for conflict detection.
- After each successful provide, the plugin logs a summary with the server version and the counts of new, updated and deleted endpoints.
- A local state cache at `target/.sanshain-cache.json`, which records each spec's content hash and server version and each require response's ETag.
- `provide` skips the upload when a spec is unchanged since the last successful provide.
- When no `baseVersion` is configured, `provide` sends the last known server version as `base_version`.
- `require` sends `If-None-Match` with the cached ETag and leaves output files untouched on `304 Not Modified`.
- `strict` parameter (`-Dsanshain.strict`) that makes missing configuration fail the build.

### Changed
- The plugin now runs on Java 11 or newer instead of requiring Java 17.
- Without strict mode, a missing `serviceName`, an empty `provides` or an empty `requires` now logs a warning and skips the goal instead of failing the build.
- A `409 Conflict` from provide now fails with a concurrent-modification message instead of the server's response body.

## [1.3.0] - 2026-04-23

### Added
- `branch` field on `requires` entries to require from a specific branch.
- Branch detection reads common CI variables for GitHub Actions, GitLab CI, Jenkins, Bitbucket Pipelines, Azure DevOps, Travis CI and CircleCI.
- Debug logging of uncompressed request bodies and response headers.

### Fixed
- A detached HEAD is resolved to a branch name through Git refs or the git CLI instead of sending a commit SHA as the branch.

## [1.2.0] - 2026-04-22

### Added
- `provide` and `require` can run without a `pom.xml`.
- Every provide and require request now sends `api_type` to the Sanshain service, defaulting to `openapi`.

### Fixed
- Server error responses are decompressed, cut to 1000 characters and stripped of non-printable characters before they are logged or shown in build errors.

## [1.0.0] - 2026-04-22

### Added
- `provide` uploads AsyncAPI and Protocol Buffers specs as well as OpenAPI, to `/provide/asyncapi` and `/provide/grpc`.
- `provides` list in `sanshain.yaml` with `file`, `apiType` and `branch` per entry.
- `asyncApiFile` and `protoFile` fields in the single `provide` section.
- `apiType` field on `requires` entries, which uses `/require/asyncapi` or `/require/grpc` for single endpoints and sends `api_type` for bundles.
- Required Protocol Buffers specs are saved with a `.proto` extension.
- `bestEffort` option in `sanshain.yaml` and `SANSHAIN_BEST_EFFORT`, which logs Sanshain errors as warnings instead of failing the build.
- `SANSHAIN_SERVICE_NAME` environment variable.

### Changed
- Top-level `serviceName` in `sanshain.yaml` now names the service for both goals; `clientName` and `SANSHAIN_CLIENT_NAME` are still accepted as aliases.
- The `require` goal's `clientName` parameter (`sanshain.client.name`) is now `serviceName` (`sanshain.service.name`).
- When no spec is configured and the default `target/openapi.yaml` is missing, `provide` logs a warning instead of failing.

### Removed
- `serviceName` inside the `provide` section of `sanshain.yaml` is no longer read.

## [0.7.0] - 2026-04-21

### Added
- `insecure` option (`sanshain.yaml` field, `-Dsanshain.insecure`, `$SANSHAIN_INSECURE`, default `false`) that turns off TLS certificate and hostname checks, for example to accept self-signed certificates, and logs a warning when enabled.

### Changed
- HTTP redirects from the Sanshain service are now followed.

## [0.6.0] - 2026-04-02

### Added
- Dry-run mode via `-Dsanshain.dry.run=true`: `provide` validates the spec without storing it, and `require` looks up endpoints without recording dependencies (`dry_run` is sent to the service).
- `-Dsanshain.branch` property that sets the branch and takes precedence over `$SANSHAIN_BRANCH` and Git detection.

### Changed
- Renamed Maven properties: `serviceName` is now `sanshain.service.name`, `openApiFile` is now `sanshain.openapi.file`, and `clientName` is now `sanshain.client.name`.
- Failed `provide`, `require` and bundle requests now log the service's error message at ERROR level and include it in the build failure.
- A 404 from `require` now reports the service's explanation instead of "server timed out".

## [0.5.0] - 2026-04-02

### Added
- The Sanshain URL can be set as `<configuration><sanshainUrl>` in the matching `settings.xml` server entry; this beats `$SANSHAIN_URL` and `sanshain.yaml`, but `-Dsanshain.url` still wins.

## [0.4.0] - 2026-04-01

### Added
- `sanshain.skip` property that skips both goals, plus `sanshain.provide.skip` and `sanshain.require.skip` to skip one goal.
- Warning when no authentication token is configured.
- Debug output (`mvn -X`) of resolved settings and HTTP request and response details, with the token masked.

### Changed
- `provide` now runs in the `initialize` phase by default instead of `package`.

## [0.3.0] - 2026-04-01

### Added
- When a required service lists 2 or more endpoints, `require` fetches them in one `POST /require-bundle` call and saves a merged spec with deduplicated schemas as `{serviceName}_bundle.yaml`.

### Fixed
- Builds outside a Git repository no longer fail during branch detection and fall back to `main`.
- OpenAPI specs containing unusual control characters are now encoded correctly in the `provide` upload.

## [0.2.1] - 2026-03-18

### Added
- `sanshain:provide` goal that uploads the service's OpenAPI spec to the Sanshain service, bound to the `package` phase by default.
- `sanshain:require` goal that downloads the OpenAPI snippet for each configured endpoint and saves it as `{serviceName}_{path}_{method}.yaml`, bound to the `generate-sources` phase by default.
- `sanshain.yaml` in the project root, with a different location set by `configFile`, holding `sanshainUrl`, `timeout`, `compression`, `clientName`, `provide` (`serviceName`, `openApiFile`) and `requires` (`serviceName`, `outputDirectory`, per-service `timeout`, `endpoints` with `method` and `path`).
- Global settings can be overridden with Maven properties (`sanshain.url`, `sanshain.timeout`, `sanshain.compression`, `clientName`, `serviceName`, `openApiFile`) and environment variables (`SANSHAIN_URL`, `SANSHAIN_TIMEOUT`, `SANSHAIN_COMPRESSION`, `SANSHAIN_CLIENT_NAME`).
- Bearer-token authentication, taken first from `$SANSHAIN_TOKEN`, then from the password of the `settings.xml` server entry selected by `sanshain.serverId` (default `sanshain`), then from `-Dsanshain.token`.
- Branch is detected from Git, can be overridden with `$SANSHAIN_BRANCH`, and defaults to `main`.
- `require` uses server-side long-polling, waiting up to `timeout` seconds (default 120), and fails the build if the endpoint is not found.
- Gzip compression of uploads and downloads, on by default.
