# Corporate & Enterprise Usage

In corporate environments, flexibility and security are paramount. This guide covers best practices for configuring the Sanshain Maven Plugin in large-scale microservice architectures.

## Flexible Configuration

Avoid hardcoding infrastructure details (like the Sanshain service URL) or sensitive information (like tokens) in your project's `sanshain.yaml` or `pom.xml`. Instead, use environment-specific overrides.

### Using `settings.xml` (Recommended)

The recommended approach for defining where the Sanshain service is running and how to authenticate is via Maven's `settings.xml`. This keeps infrastructure configuration out of the repository.

```xml
<settings>
  <servers>
    <server>
      <id>sanshain</id>
      <username>ignored</username>
      <password>san_abc123...</password>
      <configuration>
        <sanshainUrl>https://sanshain.example.com</sanshainUrl>
      </configuration>
    </server>
  </servers>
</settings>
```

- **`<id>`**: Must match the `serverId` parameter in the plugin configuration (default: `sanshain`).
- **`<password>`**: Holds the Sanshain authentication token.
- **`<sanshainUrl>`**: Sets the service URL for all projects using this `serverId`.

### Environment Variables

CI/CD pipelines can easily inject configuration using environment variables:

| Variable                | Description                                         |
|-------------------------|-----------------------------------------------------|
| `SANSHAIN_URL`          | URL of the Sanshain service                         |
| `SANSHAIN_TOKEN`        | Authentication token                                |
| `SANSHAIN_BRANCH`       | Branch name (auto-detected if not set)              |
| `SANSHAIN_FORCE`        | Enable force mode (reset shared contract)           |
| `SANSHAIN_BEST_EFFORT`   | Don't fail the build on server errors               |

## CI/CD Pipeline Integration

### GitHub Actions Example

```yaml
- name: Build with Maven
  run: mvn verify
  env:
    SANSHAIN_URL: ${{ secrets.SANSHAIN_URL }}
    SANSHAIN_TOKEN: ${{ secrets.SANSHAIN_TOKEN }}
    SANSHAIN_BRANCH: ${{ github.head_ref || github.ref_name }}
```

### Dry-Run for Pull Requests

To validate that a feature branch is compatible with the target branch before merging, use `dryRun`:

```bash
mvn verify -Dsanshain.branch=main -Dsanshain.dry.run=true
```

## Multi-Module Projects & Parent POMs

For large organizations, you can apply the Sanshain plugin across all projects by adding it to a company-wide parent POM.

### Parent POM Configuration

Combine with `bestEffort` mode to ensure that projects not yet using Sanshain are not blocked.

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.paxel.sanshain</groupId>
            <artifactId>sanshain-maven-plugin</artifactId>
            <version>1.11.1</version>
            <executions>
                <execution>
                    <phase>validate</phase>
                    <goals>
                        <goal>provide</goal>
                    </goals>
                </execution>
                <execution>
                    <phase>initialize</phase>
                    <goals>
                        <goal>require</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <bestEffort>true</bestEffort>
            </configuration>
        </plugin>
    </plugins>
</build>
```

- **Safety**: If a child project lacks `sanshain.yaml`, the plugin logs a warning and continues.
- **Opt-out**: Child projects can disable the plugin by setting `<sanshain.skip>true</sanshain.skip>`.
    
## Understanding the Build Cache

The Sanshain Maven Plugin uses a local cache in the `target/` directory to improve build performance and avoid redundant network calls.

### How it Works
1.  **Hash Comparison**: Before uploading a specification, the plugin computes a hash of the content and compares it against the last successful upload stored in `target/sanshain-cache.yaml` (or similar).
2.  **Skipping**: If the content hash matches the cached version, the plugin logs: `⏭ Spec unchanged (hash match), skipping provide.`
3.  **Idempotency**: This ensures that even if `mvn install` is run multiple times, the Sanshain service only receives updates when the API actually changes.

### Clearing the Cache
To force a re-upload or re-download of all snippets, simply run a clean build:
```bash
mvn clean install
```
Since the cache is stored in `target/`, the `clean` goal will remove it, forcing the plugin to synchronize with the server.
