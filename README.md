# SanShain Maven Plugin

The SanShain Maven Plugin allows microservices to interact with the SanShain service to manage and distribute OpenAPI specifications during the build process.

## Goals

- `sanshain:provide`: Uploads a full OpenAPI specification to the SanShain service.
- `sanshain:require`: Downloads specific endpoint snippets from the SanShain service.

## Configuration
 
 Add the following to your `pom.xml`:
 
 ```xml
 <plugin>
     <groupId>com.sanshain</groupId>
     <artifactId>sanshain-maven-plugin</artifactId>
     <version>0.1.0-SNAPSHOT</version>
 </plugin>
 ```
 
 ### External Configuration (`sanshain.yaml`)
 
 To keep your `pom.xml` clean, you can use a `sanshain.yaml` file in your project's root directory. The plugin will automatically look for this file.
 
 #### `sanshain.yaml` Structure
 
 ```yaml
 sanshainUrl: http://localhost:8080
 clientName: order-service

 provide:
   serviceName: user-service
   openApiFile: src/main/resources/openapi.yaml

 require:
  - outputDirectory: target/generated-sources/sanshain
    requirements:
       - serviceName: user-service
         path: /users/{id}
         method: get
 ```

 The `branch` is automatically detected from Git or can be overridden via the `SANSHAIN_BRANCH` environment variable.
 
 If you use `sanshain.yaml`, your `pom.xml` can be as simple as:
 
 ```xml
 <plugin>
     <groupId>com.sanshain</groupId>
     <artifactId>sanshain-maven-plugin</artifactId>
     <version>0.1.0-SNAPSHOT</version>
     <executions>
         <execution>
             <goals>
                 <goal>require</goal>
                 <goal>provide</goal>
             </goals>
         </execution>
     </executions>
 </plugin>
 ```
 
 You can override the configuration file location using the `<configFile>` parameter.
 
 ### Goal: `provide`

This goal is typically used by a service provider to upload its OpenAPI definition. It defaults to the `package` phase.

#### Parameters

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `serviceName` | `serviceName` | - | **Required.** The name of the service. |
| `openApiFile` | `openApiFile` | `${project.build.directory}/openapi.yaml` | Path to the OpenAPI YAML file. |
| `sanshainUrl` | `sanshainUrl` | `http://localhost:8080` | URL of the SanShain service. |

The `branch` is automatically detected from Git or can be overridden via the `SANSHAIN_BRANCH` environment variable.

#### Example

```xml
<plugin>
    <groupId>com.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>provide</goal>
            </goals>
            <configuration>
                <serviceName>user-service</serviceName>
                <openApiFile>${project.basedir}/src/main/resources/openapi.yaml</openApiFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Goal: `require`

This goal is used by a client service to download only the necessary OpenAPI snippets for the endpoints it consumes. It defaults to the `generate-sources` phase.

#### Parameters

| Parameter | Property | Default | Description |
|-----------|----------|---------|-------------|
| `clientName` | `clientName` | - | **Required.** The name of the client service. |
| `requirements` | - | - | **Required.** List of requested endpoints. |
| `outputDirectory` | `outputDirectory` | `${project.build.directory}/generated-sources/sanshain` | Where to save the downloaded snippets. |
| `timeout` | `timeout` | `300` | Maximum time (seconds) to wait for requirements. |
| `retryInterval` | `retryInterval` | `10` | Time (seconds) between retries. |
| `sanshainUrl` | `sanshainUrl` | `http://localhost:8080` | URL of the SanShain service. |

#### Example

```xml
<plugin>
    <groupId>com.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>require</goal>
            </goals>
            <configuration>
                <clientName>order-service</clientName>
                <requirements>
                    <requirement>
                        <serviceName>user-service</serviceName>
                        <path>/users/{id}</path>
                        <method>get</method>
                    </requirement>
                    <requirement>
                        <serviceName>inventory-service</serviceName>
                        <path>/stock/{sku}</path>
                        <method>get</method>
                    </requirement>
                </requirements>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### Combined Example

If your service is both a provider (exposes an API) and a client (consumes other APIs), you can configure both goals in separate executions. This is a common scenario in microservice architectures.

```xml
<plugin>
    <groupId>com.sanshain</groupId>
    <artifactId>sanshain-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <!-- 1. Download requirements before generating sources -->
        <execution>
            <id>fetch-requirements</id>
            <goals>
                <goal>require</goal>
            </goals>
            <configuration>
                <clientName>order-service</clientName>
                <requirements>
                    <requirement>
                        <serviceName>user-service</serviceName>
                        <path>/users/{id}</path>
                        <method>get</method>
                    </requirement>
                </requirements>
            </configuration>
        </execution>
        <!-- 2. Provide own OpenAPI spec after packaging -->
        <execution>
            <id>provide-api</id>
            <goals>
                <goal>provide</goal>
            </goals>
            <configuration>
                <serviceName>order-service</serviceName>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Integrating with OpenAPI Generator

The downloaded snippets can be used by the `openapi-generator-maven-plugin` to generate client code. Since the generator typically expects a single OpenAPI file, you might need to combine them or run the generator for each snippet.

The generated code should be placed in the `target` directory (e.g., `target/generated-sources/openapi`) so that it's correctly handled by the build process and ignored by version control.

### Example: Generating a Client

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.0.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <!-- Point to a snippet or a combined file in the sanshain output directory -->
                <inputSpec>${project.build.directory}/generated-sources/sanshain/user-service-main-users-id-get.yaml</inputSpec>
                <generatorName>java</generatorName>
                <library>resttemplate</library>
                <output>${project.build.directory}/generated-sources/openapi</output>
                <apiPackage>com.sanshain.client.user.api</apiPackage>
                <modelPackage>com.sanshain.client.user.model</modelPackage>
                <generateApiTests>false</generateApiTests>
                <generateModelTests>false</generateModelTests>
                <configOptions>
                    <dateLibrary>java8</dateLibrary>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## License

This project is licensed under the GNU Affero General Public License (AGPL-3.0). See the [LICENSE](LICENSE) file for details.
