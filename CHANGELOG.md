# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).


## [2.5.0]

### Added
- `autoGa: true` in `sanshain.yaml` makes a provide GA whenever the build declares trunk or a tag; `-Dsanshain.ga=false` or `SANSHAIN_GA=false` keeps a build a snapshot.
- `annotations` in `sanshain.yaml` travel with provide, require, bundle and catalog push, their `{{NAME}}` placeholders filled from `-DNAME=value` or `SANSHAIN_SET_NAME` (needs Sanshain Service 2.13.0 or later).

### Changed
- An explicit `-Dsanshain.ga=false` now keeps a build a snapshot even when `SANSHAIN_GA=true` is set.

### Fixed
- A retire the service accepts with an empty or unreadable `202` body now succeeds instead of failing with "Failed to build JSON for /provide".
- A require with `apiType: grpc` is saved as `.proto` (it was `.yaml`), and its bundle sends `api_type: proto`, the spelling the service's contract names.

---

Historical changes have been moved to [OLDER_CHANGES.md](OLDER_CHANGES.md).
