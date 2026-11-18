package com.sanshain.maven;

import com.code_intelligence.jazzer.junit.FuzzTest;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reads {@code sanshain.yaml} with any content: the file is loaded, or it is refused with a
 * {@link MojoExecutionException} that names the problem. Anything else is a bug.
 *
 * <p>The inputs in {@code src/test/resources/com/sanshain/maven/ConfigFuzzTestInputs} run with
 * every test run; {@code JAZZER_FUZZ=1 mvn test -Dtest=ConfigFuzzTest} fuzzes.</p>
 */
class ConfigFuzzTest {

    @FuzzTest
    void config(byte[] data) throws IOException {
        Path directory = Files.createTempDirectory("sanshain-config-fuzz");
        Path file = directory.resolve("sanshain.yaml");
        try {
            Files.write(file, data);
            SanshainConfig config = new SanshainConfig().loadConfig(file.toFile());
            assertNotNull(config.getUnknownFields());
        } catch (MojoExecutionException refused) {
            assertNotNull(refused.getMessage());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
