# Development Guidelines for Sanshain Maven Plugin

## Build and Configuration

### Build Requirements
- **Java 17** or higher.
- **Maven 3.8.1** or higher.

### Project Build
To build the plugin and install it in your local repository:
```bash
mvn clean install
```
This will also run all tests, generate the plugin JAR, sources, and Javadoc.

### Plugin Configuration
The plugin can be configured via `sanshain.yaml` in the project root, Maven properties in `pom.xml`, or environment variables.

#### `sanshain.yaml` Structure:
```yaml
sanshainUrl: https://sanshain.example.com
clientName: my-service
timeout: 120
compression: true

provide:
  serviceName: my-service
  openApiFile: src/main/resources/openapi.yaml

requires:
  - serviceName: other-service
    outputDirectory: target/generated-sources/sanshain
    endpoints:
      - method: GET
        path: /api/v1/resource
```

#### Environment Variable Overrides:
- `SANSHAIN_URL`: Overrides `sanshainUrl`.
- `SANSHAIN_CLIENT_NAME`: Overrides `clientName`.
- `SANSHAIN_TIMEOUT`: Overrides global `timeout`.
- `SANSHAIN_COMPRESSION`: Overrides `compression` (true/false).
- `SANSHAIN_TOKEN`: Authentication token for the service.
- `SANSHAIN_BRANCH`: Overrides automatic Git branch detection.

#### Authentication:
The plugin looks for the token in:
1. `SANSHAIN_TOKEN` environment variable.
2. `settings.xml` server entry matching the `serverId` (default: `sanshain`). The `password` field is used as the token.
3. `<sanshain.token>` property in `pom.xml`.

## Testing Information

### Running Tests
Use the standard Maven command to run tests:
```bash
mvn test
```

### Adding New Tests
Tests are located in `src/test/java`. We use **JUnit 5**.

#### Example Test:
A simple test to verify configuration loading:
```java
package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class ConfigLoaderTest {
    @TempDir Path tempDir;

    @Test
    public void testLoadConfig() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                      "clientName: test-client";
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);

        SanshainConfig config = new SanshainConfig().loadConfig(configPath.toFile());
        assertEquals("https://api.sanshain.com", config.getSanshainUrl());
        assertEquals("test-client", config.getClientName());
    }
}
```

## Additional Development Information

### Code Style
- Follow standard Java coding conventions.
- Indentation: 4 spaces.
- Use meaningful variable names.
- Document Mojos using Javadoc tags (e.g., `@Mojo`, `@Parameter`).
- No utility classes. Functionality should be placed within domain objects or Mojos.
- No unnecessary constructors. Use default constructors only when required by frameworks (like Jackson) and when they are not automatically provided.

### Key Components
- `ProvideMojo`: Implements the `sanshain:provide` goal.
- `RequireMojo`: Implements the `sanshain:require` goal.
- `SanshainConfig`: Root configuration object, also handles its own loading from YAML and environment overrides.
- `SanshainHttpClient`: Handles communication with the Sanshain service using Java's built-in `HttpClient`.

### Git Branch Detection
The plugin uses `JGit` to automatically detect the current Git branch if `SANSHAIN_BRANCH` is not provided. It falls back to `main` if no repository is found.
