# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).


## [2.2.0] - Unreleased

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

---

Historical changes have been moved to [OLDER_CHANGES.md](OLDER_CHANGES.md).
