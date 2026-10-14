# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).


## [2.2.0] - Unreleased

### Added
- `sanshain:catalog` goal: pushes the service's Backstage `catalog-info.yaml`, named by the new `catalogInfo` field in `sanshain.yaml`, so the network graph shows the peripheral resources it depends on; a trunk build updates the main graph as well as the dev graph, and `-Dsanshain.dry.run=true` only checks the file locally.
- `catalog` requires Sanshain Service 2.4.0; all other commands work with any 2.x service.
- `strict: true` in `sanshain.yaml` turns on strict mode; an explicitly set `-Dsanshain.strict` (true or false) overrides it.

### Changed
- Relative paths in `sanshain.yaml` (`file`, `openApiFile`, `asyncApiFile`, `protoFile`, `outputDirectory`, `catalogInfo`) resolve against the directory containing `sanshain.yaml` instead of the project base directory; with the default `configFile` nothing moves.
- Unknown fields in `sanshain.yaml` no longer fail the build: a warning names them and they are ignored, unless `sanshain.strict` is on, which fails the goal.
- `provide` always uploads; the client-side content-hash cache that skipped unchanged specs is gone (an identical re-provide is a no-op on the server).
- `SANSHAIN_*` boolean environment variables are on for `true` or `1` in any case and with surrounding whitespace; any other value is off.

### Fixed
- A dry-run `provide` no longer makes the following real `provide` log "Spec unchanged" and publish nothing.
- Absolute paths in `sanshain.yaml` are used as given instead of being appended to the project directory.

---

Historical changes have been moved to [OLDER_CHANGES.md](OLDER_CHANGES.md).
