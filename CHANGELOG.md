# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).


## [2.5.1]

### Added
- The release on GitHub carries the plugin jar, the sources jar, the javadoc jar and the POM with a CycloneDX SBOM of the source, a build provenance attestation and its Sigstore bundle; `gh attestation verify <file> -R paxel/sanshain-maven-plugin` checks a download, and the jars on Maven Central are byte for byte the attested ones ([sanmvn-1005](https://github.com/paxel/sanshain-maven-plugin/issues/20)). Apart from the lines below, the goals behave as in 2.5.0.

### Changed
- When the `target` directory cannot be created, the error of a require names that directory; it named the state file that could not be written ([sanmvn-1000](https://github.com/paxel/sanshain-maven-plugin/issues/15)).

### Fixed
- A `sanshain.yaml` that holds nothing but `~`, `null` or a bare `!` is refused with `Failed to load configuration from <file>`, like any other file that is not a configuration; the goal failed with a `NullPointerException` ([sanmvn-1003](https://github.com/paxel/sanshain-maven-plugin/issues/18)).
- A state file `target/.sanshain-cache.json` that holds `null` or `"requires": null` counts as empty, like any other state file that cannot be read; the require failed with a `NullPointerException` ([sanmvn-1003](https://github.com/paxel/sanshain-maven-plugin/issues/18)).
- A require with `format: openapi` against a service that reports `.` as its version fails with the message that names the version; it failed with an `ArrayIndexOutOfBoundsException` ([sanmvn-1003](https://github.com/paxel/sanshain-maven-plugin/issues/18)).

---

Historical changes have been moved to [OLDER_CHANGES.md](OLDER_CHANGES.md).
