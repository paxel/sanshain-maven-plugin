package com.sanshain.maven;

import com.code_intelligence.jazzer.junit.FuzzTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reads the require state file {@code .sanshain-cache.json} with any content: always a state
 * whose requires can be looked up and written.
 *
 * <p>The inputs in {@code src/test/resources/com/sanshain/maven/StateFuzzTestInputs} run with
 * every test run; {@code JAZZER_FUZZ=1 mvn test -Dtest=StateFuzzTest} fuzzes.</p>
 */
class StateFuzzTest {

    @FuzzTest
    void state(byte[] data) throws IOException {
        Path directory = Files.createTempDirectory("sanshain-state-fuzz");
        Path file = directory.resolve(".sanshain-cache.json");
        try {
            Files.write(file, data);
            SanshainCache cache = new SanshainCache(directory.toFile());
            cache.getRequireEntry("key");
            cache.updateRequireEntry("key", "etag");
            SanshainCache.RequireEntry entry = cache.getRequireEntry("key");
            assertNotNull(entry);
            assertEquals("etag", entry.etag);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
