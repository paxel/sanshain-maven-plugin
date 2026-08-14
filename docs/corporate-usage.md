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

| Variable                | Description                                              |
|-------------------------|----------------------------------------------------------|
| `SANSHAIN_URL`          | URL of the Sanshain service                              |
| `SANSHAIN_TOKEN`        | Authentication token                                     |
| `SANSHAIN_GA`           | Provide as `ga` instead of `snapshot` (release pipelines) |
| `SANSHAIN_BEST_EFFORT`  | Don't fail the build on server errors                    |

## CI/CD Pipeline Integration

Stability is declared per build: feature pipelines publish overwritable `snapshot` versions by default, and the release/protected-branch pipeline sets the GA switch. That is the whole mechanism — there is no branch detection.

### GitHub Actions Example

```yaml
- name: Build with Maven
  run: mvn verify
  env:
    SANSHAIN_URL: ${{ secrets.SANSHAIN_URL }}
    SANSHAIN_TOKEN: ${{ secrets.SANSHAIN_TOKEN }}
    # GA only on the release branch pipeline
    SANSHAIN_GA: ${{ github.ref == 'refs/heads/main' }}
```

### Dry-Run for Pull Requests

To validate that a changed spec would be accepted by the version rules (GA immutability, semver honesty) before merging, use `dryRun`:

```bash
mvn verify -Dsanshain.dry.run=true
```

A `409` response names the `proposed_version` to publish as instead — the fix is always a version bump in the spec file itself.

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
            <version>2.1.0</version>
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
1.  **Hash Comparison**: Before uploading a specification, the plugin computes a hash of the content and compares it against the last successful upload stored in `target/.sanshain-cache.json`.
2.  **Skipping**: If the content hash matches the cached version, the plugin logs: `⏭ Spec unchanged (hash match), skipping provide.`
3.  **ETag Caching**: Require responses are cached by `ETag`; an unchanged pin answers `304 Not Modified` and the output file is not rewritten. A pin on a GA version can never change content; a pin on a snapshot can, which is exactly what the ETag detects.
4.  **Idempotency**: Even without the cache, re-providing byte-identical content is a server-side no-op — CI re-runs of the same commit never fight.

### Clearing the Cache
To force a re-upload or re-download of all snippets, simply run a clean build:
```bash
mvn clean install
```
Since the cache is stored in `target/`, the `clean` goal will remove it, forcing the plugin to synchronize with the server.
