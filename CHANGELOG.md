# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).


## [2.4.0]

### Added
- A provide carries the origin of its build: the build URL and the pull or merge request URL from the runner's own variables on GitHub Actions, GitLab CI and Jenkins (overridden by `-Dsanshain.buildUrl` / `SANSHAIN_BUILD_URL` and `-Dsanshain.changeUrl` / `SANSHAIN_CHANGE_URL`), plus `-Dsanshain.tickets` / `SANSHAIN_TICKETS` (comma-separated ids) and `-Dsanshain.note` / `SANSHAIN_NOTE`. Sanshain Service 2.9.0 and later stores it with the version and shows it as "why".

### Changed
- Snapshots are overwritable by the user who last provided them (Sanshain Service 2.9.0 and later); another user's different content is rejected `409` with the next free minor proposed, which the plugin prints like every other version-rule rejection.

---

Historical changes have been moved to [OLDER_CHANGES.md](OLDER_CHANGES.md).
