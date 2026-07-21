# Code Generation Guide

Integrating Sanshain with code generation tools is the best way to ensure type-safety and consistency between your microservices. This guide demonstrates how to use the downloaded OpenAPI snippets with popular Java frameworks.

## Spring Boot Integration

For Spring Boot 3.2+ (Spring 6.1+), we recommend using the `RestClient` for clients and `interfaceOnly` mode for servers.

### Client Generation (RestClient)

Use the `openapi-generator-maven-plugin` with the `restclient` library.

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.2.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <inputSpec>${project.build.directory}/generated-sources/sanshain/user-service_bundle.yaml</inputSpec>
                <generatorName>java</generatorName>
                <library>restclient</library>
                <configOptions>
                    <useSpringBoot3>true</useSpringBoot3>
                    <interfaceOnly>true</interfaceOnly>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Server Generation (Interface Only)

If you are implementing a service, generate the interfaces to ensure you stay compliant with the contract provided to Sanshain.

```xml
<configuration>
    <inputSpec>${project.build.directory}/generated-sources/sanshain/my-service_bundle.yaml</inputSpec>
    <generatorName>spring</generatorName>
    <configOptions>
        <interfaceOnly>true</interfaceOnly>
        <useSpringBoot3>true</useSpringBoot3>
        <useTags>true</useTags>
    </configOptions>
</configuration>
```

## Quarkus Integration

Quarkus provides a specialized extension for OpenAPI client generation that works perfectly with Sanshain snippets.

### Using `quarkus-openapi-generator`

Add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>io.quarkiverse.openapi.generator</groupId>
    <artifactId>quarkus-openapi-generator</artifactId>
    <version>2.6.0</version>
</dependency>
```

Then configure the plugin to point to the Sanshain output:

```xml
<plugin>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-maven-plugin</artifactId>
    <version>${quarkus.platform.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>generate-code</goal>
            </goals>
            <configuration>
                <!-- The extension automatically finds YAML files in specific locations, 
                     or you can configure it explicitly -->
                <inputSpec>${project.build.directory}/generated-sources/sanshain/user-service_bundle.yaml</inputSpec>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Best Practices

### Use Bundles for Clients
Always require multiple endpoints from the same service if you need more than one. Sanshain will provide a `_bundle.yaml` which deduplicates DTOs, preventing class name collisions in your generated code.

### Automated Workflow
Ensure that the `sanshain:require` goal runs in the `initialize` phase (default) and that your code generator is configured to run after it (e.g., in `generate-sources`).

| Tool              | Recommendation                                      | Key Configuration                   |
|-------------------|-----------------------------------------------------|-------------------------------------|
| Spring Boot       | `java` generator with `restclient` library          | `library=restclient`                |
| Spring Boot       | `spring` generator for servers                      | `interfaceOnly=true`                |
| Quarkus           | `quarkus-openapi-generator` extension               | Native integration, reactive ready  |
| Microprofile      | Standard `openapi-generator` with `microprofile`    | `library=microprofile`              |
