# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).


## [2.5.0]

### Added
- `autoGa: true` in `sanshain.yaml` makes a provide GA whenever the build declares trunk or a tag; `-Dsanshain.ga=false` or `SANSHAIN_GA=false` keeps a build a snapshot.
- `annotations` in `sanshain.yaml` travel with provide, require, bundle and catalog push, their `{{NAME}}` placeholders filled from `-DNAME=value` or `SANSHAIN_SET_NAME` (needs Sanshain Service 2.13.0 or later).

### Changed
- An explicit `-Dsanshain.ga=false` now keeps a build a snapshot even when `SANSHAIN_GA=true` is set.
- Without `sanshain.yaml`, a goal that lacks something it needs (a service name, a spec file to provide, `requires`, `catalogInfo`) fails with `Configuration file <path> not found: <message>` instead of warning and skipping; best effort skips it with a warning, and strict fails it also with best effort. A build configured completely in the POM (`sanshain.service.name`, `sanshain.url`, `target/openapi.yaml`) runs as before.

### Fixed
- A retire the service accepts with an empty or unreadable `202` body now succeeds instead of failing with "Failed to build JSON for /provide".
- A require with `apiType: grpc` is saved as `.proto` (it was `.yaml`), and its bundle sends `api_type: proto`, the spelling the service's contract names.
- Best effort softens failed calls, the same in provide, require and catalog, and a failed provide no longer stops the next `provides` entry. A broken `sanshain.yaml` (unreadable, invalid, 1.x fields, an unsupported `apiType`) fails before any call; best effort from `-Dsanshain.bestEffort` or `SANSHAIN_BEST_EFFORT` skips the goal with `Sanshain goal skipped (best effort): <message>` instead of going on with an empty configuration. The broken file's own `bestEffort: true` does not count, and `-Dsanshain.strict=true` fails it also with best effort. Missing configuration under strict now fails also with best effort.
- A `403` on provide or retire names the releaser role only when the service refused for want of it; any other `403`, such as a read-only token's, shows the service's message alone.
- The require ETag cache is kept per output directory: the same endpoint required into two directories is written to both instead of the second getting a `304` and staying empty, and a deleted output file is fetched again instead of being skipped.
- The GitHub Actions example in `docs/corporate-usage.md` sets `SANSHAIN_GA` on main only and leaves it empty elsewhere; the `false` it set off main kept `autoGa` from making a build GA.
- `docs/lifecycle.md` names `initialize` as provide's default phase, as the plugin binds it; it said `validate`.
- Without a configured URL the plugin calls `http://localhost:3000`, the port Sanshain listens on; it called `http://localhost:8080`.
- The POM's project and SCM URLs name the repository `github.com/paxel/sanshain-maven-plugin`; they named `github.com/sanshain/…`.
- An unknown `requires[].apiType` fails the build as a configuration error before any call, as an unknown provide `apiType` does; it was sent to `/require` as OpenAPI.
- The POM's SCM connections use `https://` and a valid `ssh://git@github.com/…` URL; GitHub no longer serves the `git://` protocol.
- The CI, release and security workflows pin every action to a full commit SHA; they referenced tags.

---

Historical changes have been moved to [OLDER_CHANGES.md](OLDER_CHANGES.md).
