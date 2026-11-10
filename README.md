# Sanshain Maven Plugin

[![CI](https://github.com/paxel/sanshain-maven-plugin/actions/workflows/ci.yml/badge.svg?branch=summer)](https://github.com/paxel/sanshain-maven-plugin/actions/workflows/ci.yml)
[![Security](https://github.com/paxel/sanshain-maven-plugin/actions/workflows/security.yml/badge.svg?branch=summer)](https://github.com/paxel/sanshain-maven-plugin/actions/workflows/security.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=paxel_sanshain-maven-plugin&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=paxel_sanshain-maven-plugin)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=paxel_sanshain-maven-plugin&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=paxel_sanshain-maven-plugin)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=paxel_sanshain-maven-plugin&metric=coverage)](https://sonarcloud.io/summary/new_code?id=paxel_sanshain-maven-plugin)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/paxel/sanshain-maven-plugin/badge)](https://scorecard.dev/viewer/?uri=github.com/paxel/sanshain-maven-plugin)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.paxel.sanshain/sanshain-maven-plugin)](https://central.sonatype.com/artifact/io.github.paxel.sanshain/sanshain-maven-plugin)
[![Java 11+](https://img.shields.io/badge/java-11%2B-blue)](https://github.com/paxel/sanshain-maven-plugin/blob/summer/src/test/java/com/sanshain/maven/PluginDescriptorTest.java)
[![Maven 3.6.3+](https://img.shields.io/badge/maven-3.6.3%2B-blue)](https://github.com/paxel/sanshain-maven-plugin/blob/summer/src/test/java/com/sanshain/maven/PluginDescriptorTest.java)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](https://github.com/paxel/sanshain-maven-plugin/blob/summer/LICENSE)

The Sanshain Maven Plugin connects a Maven build to a [Sanshain](https://github.com/paxel/sanshain-service) service, which manages and distributes API specifications (OpenAPI, AsyncAPI, gRPC/Proto). A **Producer** provides its complete specification under the version declared in the spec file; a **Consumer** pins an exact version and requires only the endpoint snippets it uses.

**The documentation lives on the Sanshain site: [Maven client](https://paxel.github.io/sanshain-service/developer/clients/maven.html).** It lists every goal, property and variable with the plugin version it arrived in, the precedence, caching, strict and best-effort modes, the failure modes and CI recipes. The file itself is described in [`sanshain.yaml`](https://paxel.github.io/sanshain-service/developer/sanshain-yaml.html).

Plugin 2.x speaks Sanshain Service 2.x. It needs Java 11 or later and Maven 3.6.3 or later.

## Install

Add the plugin to `pom.xml`:

```xml
<plugin>
    <groupId>io.github.paxel.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>2.5.0</version>
    <executions>
        <execution>
            <phase>initialize</phase>
            <goals>
                <goal>provide</goal>
                <goal>require</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## A minimal example

`sanshain.yaml` next to `pom.xml`:

```yaml
serviceName: order-service
provides:
  - file: src/main/resources/openapi.yaml   # the version is read from info.version
requires:
  - serviceName: user-service
    version: 2.3.0
    outputDirectory: target/generated-sources/sanshain/user-service
    endpoints:
      - method: GET
        path: /api/v1/users/{id}
```

The URL and the token come from the environment (or from `settings.xml`, see the site):

```bash
export SANSHAIN_URL=https://sanshain.example.com
export SANSHAIN_TOKEN=san_xxxxxxxxxxxx
mvn verify                          # provides a snapshot, requires the pinned snippets
mvn verify -Dsanshain.ga=true       # the release pipeline provides GA
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
