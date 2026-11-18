# sanshain-maven-plugin moves to the gates standard

Decided on 2026-11-18.

sanshain-maven-plugin had its own gates: a build workflow that ran `mvn verify` on JDK 11
and osv-scanner, a SonarQube Cloud workflow, CodeQL, Scorecard, and a release that deployed
to Maven Central from one job. Every paxel repository had done so, each differently.
[paxel/gates](https://github.com/paxel/gates) is the one standard that replaces those, as
shared workflows a repository calls, and its audit found 18 checks of it missing here
([issue 14](https://github.com/paxel/sanshain-maven-plugin/issues/14)).

sanshain-maven-plugin meets the standard of gates v1.2.0
([sanmvn-1000](https://github.com/paxel/sanshain-maven-plugin/issues/15) to
[sanmvn-1007](https://github.com/paxel/sanshain-maven-plugin/issues/22)). Where the standard
decides, it is followed without a note here: the shared OSV gate, Java gate, SonarQube Cloud
job, CodeQL, Scorecard, fuzz, release gate and provenance workflows, one coverage ratchet,
the lint config, the files and the badge row. What follows is where sanshain-maven-plugin
adds to it, or had a choice.

**The JDK.** `.java-version` pins JDK 21, which CI, the fuzz run and the release build with:
Error Prone does not run on an older one. The plugin still compiles to Java 11 bytecode,
now with `maven.compiler.release` instead of `source` and `target`, so the compiler also
refuses what the Java 11 class library does not have. It still compiles against the Maven
3.6.3 plugin API, and `PluginDescriptorTest` holds both promises in `plugin.xml`.

**The oldest supported Java.** The plugin promises Java 11, and the old build workflow
tested on it. The shared Java gate runs on the pinned JDK. So CI keeps a job of the
repository's own that runs the tests on JDK 11 with `mvn -Pjdk11 verify`. Error Prone and
SpotBugs cannot run on JDK 11; the profile `jdk11` leaves those two out and nothing else:
the sources, `-Werror -Xlint:all`, the tests and JaCoCo are those of the default build. It
adds `-proc:none`, because without the processor path of Error Prone javac looks for
annotation processors on the class path and warns that none claims the Mojo and Jackson
annotations; the build has no annotation processor. No lint category is switched off.

**Lints.** The standard's compiler arguments, Error Prone, SpotBugs and JaCoCo are taken as
they are, with the plugin versions of gates' Java fixture, and the findings were fixed in the
code, tests included. No parameter, property, goal or default changed: `plugin.xml` is the
same as before the move. Twelve SpotBugs findings are false alarms and stand in
`spotbugs-exclude.xml`, one entry per bug pattern and class with its reason: the fields of
the request payloads and of the state file entry, which only Jackson reads; `retired` of the
retire payload, which must not be static; the getters and setters of the public
configuration classes, where a copy would change what they hand out; the Maven `Settings`
the delegate keeps; and the one empty `SanshainOrigin`, which is immutable.

**Coverage.** One part, `java`, over the whole plugin, measured by the gate with JaCoCo
during `mvn verify`. The first floor is 91.94, the local measurement on JDK 21 minus 0.1.
SonarQube Cloud reads the report the gate measured; `sonar-project.properties` replaces the
properties in `pom.xml` and the scan through `sonar-maven-plugin`.

**Fuzz targets.** Four, with the names every Sanshain client uses, as Jazzer `@FuzzTest`
classes beside the tests: `ConfigFuzzTest` reads `sanshain.yaml`, `AnnotationsFuzzTest`
fills the placeholders of annotation values, `ResponseFuzzTest` reads the body, the version
and the warning headers of an answer of the service, `StateFuzzTest` reads the require state
file. Each class has one fuzz method, because Jazzer fuzzes one method per run. So that a
fuzzer reaches them without a server, the readers of `SanshainHttpClient` became
package-private static methods: `parseErrorBody`, `warningLines`, `isOlderThan240`,
`pre2Version` and `isOpenApiDocument`. Without `JAZZER_FUZZ=1` a fuzz method runs over its
saved inputs as an ordinary test, so they are part of every build and of the coverage. The
answers to a provide, a retire, a catalog push and the version call are read by Jackson
alone, inside the calls, and have no target.

**Accepted findings.** SonarQube Cloud reports the trust-all `TrustManager` of the insecure
switch under rule S4830. `pom.xml` used to silence it; it is now accepted in
`code-scanning-ignores.toml` until 2027-02-18, beside the three Scorecard findings of a
single maintainer. `osv-scanner.toml` accepts until the same day two advisories, for
commons-lang3 and plexus-utils, and the licences of Eclipse Sisu (EPL-1.0) and jsr250-api
(CDDL-1.0): all four come with the Maven 3.6.3 plugin API in `provided` scope, are supplied
by the user's Maven and never shipped. It also accepts the licence of JUnit 5 (EPL-2.0),
which is in test scope only. Jazzer is under Apache-2.0.

**Release.** The shared release gate checks alerts, findings, vulnerabilities, licences and
the changelog section, and runs no tests. A version on Maven Central cannot be replaced, so
the release first checks that the tag names the version of `pom.xml`, and its build job
runs the strict build with the tests on the tagged commit before it keeps the jars. Maven
cannot deploy files it did not build itself. `project.build.outputTimestamp` makes the build
reproducible, so the job that deploys builds the jars again, compares them byte for byte
with the attested files and deploys only when they are the same. That job alone runs in the
environment `central` and holds the GPG key and the token of Maven Central.

**What the release attaches.** The plugin jar, the sources jar, the javadoc jar and the POM,
under the names Maven Central gives them, with the SBOM of the source, a build provenance
attestation and its Sigstore bundle.

## Consequences

- A branch gets its CI run through its pull request; pushes run CI only on `summer`.
- SonarQube Cloud analyses pull requests as well as `summer`, and its quality gate now
  fails CI.
- A warning of javac, Error Prone or SpotBugs fails the build, in the tests too.
- Building the plugin needs JDK 21; `mvn -Pjdk11 verify` builds and tests it on JDK 11
  without Error Prone and SpotBugs. Using the plugin needs Java 11 and Maven 3.6.3, as
  before.
- Fuzzing, CodeQL, Scorecard and the weekly Security run come from gates; the weekly run
  also fails on SonarQube Cloud findings, secret alerts and licences.
- The release and the weekly Security run need the secret `GATES_ALERTS_TOKEN`, a
  fine-grained token that can read the repository's alerts
  ([sanmvn-1008](https://github.com/paxel/sanshain-maven-plugin/issues/23)).
- The release's SBOM and Sigstore bundle are named
  `sanshain-maven-plugin-<tag>-source.cdx.json` and
  `sanshain-maven-plugin-<tag>-provenance.sigstore.json`.
- The instruction files of earlier agents, `CLAUDE.md`, `.project-standards.md`,
  `.junie/guidelines.md`, the plans in `.junie/plans/` and `ai/plan.md`, are folded into
  `AGENTS.md` and deleted, and `.build.yml`, the build manifest for sourcehut, is gone. What
  they said about Java 17, Maven 3.8.1 and branch detection with JGit no longer held and was
  dropped.
