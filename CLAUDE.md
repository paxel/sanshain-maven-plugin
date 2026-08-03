# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Maven plugin (`io.github.paxel.sanshain:sanshain-maven-plugin`) that lets microservices push and pull OpenAPI/AsyncAPI/Protobuf contracts to/from a companion "Sanshain" service during the build. Two goals: `sanshain:provide` (upload this service's spec) and `sanshain:require` (download endpoint snippets from other services' specs). Both default to the `initialize` lifecycle phase.

Plugin 2.x speaks the Sanshain Service 2.x contract only: provides carry a declared `stability` (`snapshot`/`ga`) and the version is read from the spec file (`info.version` / `// sanshain-version:`); requires pin an exact `version`. Branches, long-polling/timeouts, `force`, and `base_version` optimistic concurrency were removed as a clean break — do not reintroduce them.

## Build & Test

```bash
mvn clean install      # build, run tests, install to local repo (produces jar + sources + javadoc)
mvn test                # run tests only
mvn test -Dtest=ProvideMojoTest                       # single test class
mvn test -Dtest=ProvideMojoTest#testSomeMethod         # single test method
mvn verify -B           # what CI runs (.github/workflows/ci.yml)
```

- Java 11 target (`pom.xml` `maven.compiler.source/target`), built with Temurin 11 in CI. Ignore any references to Java 17 elsewhere (stale).
- Tests use JUnit 5, Mockito, and WireMock (`wiremock-jre8-standalone`) to stub the Sanshain HTTP service — see `ProvideMojoTest`/`RequireMojoTest` for the pattern (spin up `WireMockServer` on a dynamic port).
- Releases are tag-triggered (`v*`) via `.github/workflows/release.yml`, publishing to Maven Central through the `release` Maven profile (GPG signing + `central-publishing-maven-plugin`).

## Architecture

All plugin code lives in `src/main/java/com/sanshain/maven/` (no sub-packages):

- **`ProvideMojo` / `RequireMojo`** — the two `@Mojo` entry points. Each declares its own `@Parameter` fields (Maven properties), then delegates all shared resolution logic rather than reimplementing it (see `.project-standards.md` — this centralization is a deliberate rule, not incidental).
- **`SanshainMojoDelegate`** — composition-based shared logic used by both Mojos: resolving URL/token/serviceName/compression/combine/bestEffort/stability from the precedence chain (Maven property → `settings.xml` → env var → `sanshain.yaml` → default), and the `abort`/`handleException` helpers that implement strict-mode vs. best-effort-mode vs. default-warn behavior. Stability is `snapshot` unless `-Dsanshain.ga=true` / `SANSHAIN_GA=true` — no git access anywhere in the plugin.
- **`SanshainConfig`** — root object for `sanshain.yaml`, loaded once per Mojo execution. Holds the `provide`/`provides` and `requires` structures, and validates at parse time: `requires` entries must carry a `version` pin, and 1.x fields (`branch`, `timeout`, `baseVersion`, `releaseBranches`, `stability`-in-yaml) are rejected by name with a migration hint before any network call.
- **`SanshainHttpClient`** — all HTTP communication with the Sanshain service (`java.net.http.HttpClient`), including gzip compression, ETag-based conditional requests for `require`, the `/require-bundle` endpoint used automatically when a service has 2+ endpoints requested (deduplicates schemas across endpoints), 409 handling that surfaces the server's `proposed_version` remedy, and a lazy, memoized `GET /version` wrong-server diagnosis on failures (pre-2.0 servers get a clear "upgrade the server" error).
- **`SpecCombiner`** — recursively inlines local `$ref`s (OpenAPI/AsyncAPI YAML/JSON) or `import` statements (proto) into a single-file spec before upload, with canonical-path cycle detection. Invoked when `combine` is enabled (default: true).
- **`SanshainCache`** — reads/writes `target/.sanshain-cache.json`: content hashes for `provide` (skip-if-unchanged) and ETags for `require` (skip rewriting output on `304`). Deleted by `mvn clean` since it lives under `target/`.
- **`ProvideResponse` / `RequireResult`** — response DTOs for the two HTTP flows.

### Key behaviors to know before changing resolution logic

- Config precedence generally goes: explicit Maven property (`-Dsanshain.*`) > `settings.xml` (URL/token only) > env var > `sanshain.yaml` > hardcoded default. `SanshainMojoDelegate` is the single source of truth for this — don't re-derive precedence inline in a Mojo.
- Three failure-handling modes interact: `strict` (fail on missing config), `bestEffort` (catch execution-time errors as warnings, doesn't cover Mojo-startup/parameter errors), and the default (warn + skip on missing config, but fail on real execution errors). See README's Strict Mode / Best-Effort Mode sections for the full matrix.
- On the wire only the canonical `producername`/`consumername` spellings exist; the server rejects unknown (1.x) fields with 422 rather than ignoring them.

## Repo conventions (`.project-standards.md`)

These are explicit "never again" rules from this project's history — treat as binding:

- No per-endpoint payload subclasses (e.g. `ProvidePayload`, `ProvideAsyncApiPayload`); use generic metadata-driven payloads (`@JsonAnyGetter`) instead.
- No dead "shadow" methods left behind after refactors — delete or fully replace old logic, don't leave near-duplicates.
- No Mojo-local parameter resolution — always go through `SanshainMojoDelegate`.
- Byte→hex conversion must be zero-padded (`String.format("%02x")`), never bare `Integer.toHexString`.
- Always specify `StandardCharsets.UTF_8` explicitly for `getBytes()`/`InputStreamReader`.
- Prefer composition (`SanshainMojoDelegate`) over inheritance between the two Mojos.
- No utility classes — functionality belongs on domain objects or Mojos directly (from `.junie/guidelines.md`).

## Docs

`docs/` has deeper guides: `configuration.md` (full `sanshain.yaml`/property/env reference), `lifecycle.md` (Maven phase integration), `code-generation.md` (OpenAPI Generator integration), `corporate-usage.md` (parent-POM/CI patterns). The root `README.md` is the canonical, up-to-date reference for all configuration and behavior — prefer it over `.junie/guidelines.md`, which describes an older config shape (`clientName`, singular `provide` block only) and is stale.

## Manual/integration testing

`scripts/` has shell scripts for exercising the plugin against a real running Sanshain service (not part of `mvn test`): `setup-demo.sh` scaffolds `demo-project/`, `provide.sh`/`require.sh` run the goals against it, `verify_cache.sh` checks the caching behavior end-to-end. These expect a live Sanshain service and are for manual verification, not CI.
