# sanshain-maven-plugin

The Maven client of the [Sanshain Service](https://github.com/paxel/sanshain-service): one
Maven plugin, `io.github.paxel.sanshain:sanshain-maven-plugin`, with the goals
`sanshain:provide`, `sanshain:require` and `sanshain:catalog`. It runs inside other people's
builds, on Java 11 and Maven 3.6.3 or later.

| Path                               | What it is                                                           |
|------------------------------------|----------------------------------------------------------------------|
| `src/main/java/com/sanshain/maven` | The plugin, one package: the classes below.                          |
| `src/test/java/com/sanshain/maven` | The tests (JUnit 5, Mockito, WireMock) and the `@FuzzTest` classes.  |
| `src/test/resources`               | The saved inputs of the fuzz tests.                                  |
| `docs/`                            | Guides beside the site: configuration, lifecycle, code generation.   |
| `demo-project/`, `scripts/`        | A sample project and shell scripts for a run against a live service. |

| Class                                                     | What it does                                                                   |
|-----------------------------------------------------------|--------------------------------------------------------------------------------|
| `ProvideMojo`, `RequireMojo`, `CatalogMojo`               | The goals: their `@Parameter` fields and the order of the steps.               |
| `SanshainMojoDelegate`                                    | Resolves every setting from its sources and holds strict mode and best effort. |
| `SanshainConfig`                                          | Reads and validates `sanshain.yaml`.                                           |
| `SanshainHttpClient`                                      | The calls to the service and the wire payloads.                                |
| `SanshainAnnotations`, `SanshainOrigin`, `SanshainStream` | What a call carries beside the document.                                       |
| `SpecCombiner`                                            | Inlines local `$ref`s and proto imports into one document before a provide.    |
| `SanshainCache`                                           | The require state file with the ETag of every require.                         |

What users rely on is the goals, their parameters and properties (`sanshain.*`), the
`SANSHAIN_` variables, what the goals log, `sanshain.yaml`, the state file, and the Java and
Maven versions `plugin.xml` asks for. The user documentation lives on the Sanshain site, in
the repository of the service.

## Agent skills

### Issue tracker

Issues live as GitHub issues in `paxel/sanshain-maven-plugin`, managed with the `gh` CLI. See
`docs/agents/issue-tracker.md`.

### Triage labels

Default canonical vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`,
`ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` plus `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Ticket IDs

Prefix `sanmvn`, as in `sanmvn-1003`. See `docs/agents/ticket-ids.md`.

## Quality gates

The standard sanshain-maven-plugin is held to is [paxel/gates](https://github.com/paxel/gates);
`docs/adr/0001-move-to-gates.md` says where sanshain-maven-plugin adds to it and why. The
workflows call gates' shared workflows, pinned by commit; Dependabot moves the pin.

### Before a commit

A change is done only when the strict build is green, on the JDK `.java-version` pins (21).
This is the command of the shared Java gate:

```sh
mvn --batch-mode --no-transfer-progress verify
```

It compiles the code and the tests with `-Werror -Xlint:all` and Error Prone, runs the tests
under JaCoCo and then SpotBugs at effort Max, threshold Low. One test class:
`mvn test -Dtest=ProvideMojoTest`.

The plugin compiles to Java 11 bytecode (`maven.compiler.release`) against the Maven 3.6.3
plugin API, and `PluginDescriptorTest` holds both. CI runs the tests on JDK 11 in a job of
its own with `mvn -Pjdk11 verify`; the profile leaves out Error Prone and SpotBugs, which do
not run there, and nothing else. Do not use what Java 11 or Maven 3.6.3 does not have, and do
not raise `maven-plugin-api.version` or JUnit to 6 as a dependency update: either raises the
floor for every user.

### How the code is written

- No payload class per endpoint. One payload with the fields all calls share, and the part
  that differs (`openapi_yaml`, `asyncapi_yaml`, …) through `@JsonAnyGetter`.
- No dead shadow methods: a method that replaces another deletes it.
- Every setting is resolved in `SanshainMojoDelegate`, from the Maven property, then
  `settings.xml` (URL and token), the environment variable, `sanshain.yaml` and the default.
  A Mojo never resolves one itself. The Mojos share the delegate by composition, not through
  a common base class.
- Bytes become hex zero-padded (`String.format("%02x")`), never through
  `Integer.toHexString` alone, and every `getBytes()` and every reader names
  `StandardCharsets.UTF_8`.
- A token is never logged above DEBUG, and there only as `[set]` or `[not set]`. The
  trust-all `TrustManager` of the insecure switch stays inside `SanshainHttpClient` and is
  built only when the switch is on.
- No utility classes: a function belongs to the domain object or the Mojo it serves.
- The plugin reads nothing from git; a build declares trunk or a tag itself.
- Plugin 2.x speaks the contract of Sanshain Service 2.x only. Branches, long polling,
  `force` and `base_version` went with 1.x and are not brought back; `sanshain.yaml` fields
  of 1.x are refused by name before any call.
- The tests stub the service with WireMock on a dynamic port; see `ProvideMojoTest`.

### Coverage ratchet

Line coverage may only go up. The Java gate in `ci.yml` fails under the floor in
`coverage-floors.toml`. Every feature ships with tests that hold or raise coverage; a
feature that lowers it is not done. When coverage rises, raise the floor and the badge in
the same pull request, with gates checked out beside the repository:

```sh
mvn --batch-mode --no-transfer-progress verify
mkdir -p coverage && python3 ../gates/scripts/to_lcov.py jacoco target/site/jacoco/jacoco.xml > coverage/java.lcov
python3 ../gates/scripts/coverage_ratchet.py --floors coverage-floors.toml
```

Measure without a `.cifuzz-corpus/` of a local fuzz run: its inputs would run as tests and
count. Never lower the floor.

### Fuzzing

The fuzz tests are Jazzer `@FuzzTest` classes beside the tests: `ConfigFuzzTest`,
`AnnotationsFuzzTest`, `ResponseFuzzTest` and `StateFuzzTest`; `fuzz.yml` runs them weekly.
Their saved inputs, in `src/test/resources/com/sanshain/maven/<Class>Inputs/<method>/`, run
with every `mvn test`. One class by hand:
`JAZZER_FUZZ=1 mvn test -Dtest=ConfigFuzzTest -Djazzer.max_duration=30s`. A crash is written
beside the saved inputs; commit it with its fix under a name that says what it is.

### Before a release

The strict build, the tests on JDK 11 (`mvn -Pjdk11 verify`), osv-scanner with gates'
`scripts/osv_gate.py` and the coverage ratchet. The version stands in `pom.xml` and in the
plugin examples of `README.md` and `docs/corporate-usage.md`. The release workflow checks
that the tag names the version of `pom.xml`, runs the shared release gate and the strict
build, and deploys to Maven Central only jars that are byte for byte the attested ones.

### Security rules

The decisions behind these are in `docs/adr/0001-move-to-gates.md`.

- A finding of javac, Error Prone or SpotBugs is fixed in the code. An Error Prone finding
  that must stay is silenced at its place with `@SuppressWarnings("<check>")` and a comment
  with the reason. A SpotBugs false alarm gets an entry in `spotbugs-exclude.xml` for that
  bug pattern and that class, with its reason; never a pattern for all classes. No lint
  category is switched off in `pom.xml`.
- Every action in `.github/workflows/` is pinned to a full commit SHA with its version in
  a trailing comment. Never reference an action by tag or branch alone.
- The JDK is pinned in `.java-version`; the shared workflows and the release read it from
  there. A JDK bump is a commit of its own.
- Every workflow starts with `permissions: contents: read`; a job that writes widens it
  itself.
- A dependency advisory is fixed by an update. When no fixed release exists, it is accepted
  in `osv-scanner.toml` with the reason sanshain-maven-plugin is not exposed and an
  `ignoreUntil` date. A licence outside the allowlist is accepted per package with
  `effectiveUntil`. Never ignore without reason and date.
- A code-scanning alert or a SonarQube Cloud finding that stays open is accepted in
  `code-scanning-ignores.toml` with its tool, rule, file, reason and an `ignoreUntil` date.
  Nothing is dismissed in GitHub or marked in SonarQube Cloud.
- The release fails on a known vulnerability, on every open code-scanning alert, secret
  alert and SonarQube Cloud finding that is not accepted, and on a missing `CHANGELOG.md`
  section for the version. The weekly Security run fails on the same, and on unmaintained
  dependencies and passed dates.
- `summer` takes pull requests only.
