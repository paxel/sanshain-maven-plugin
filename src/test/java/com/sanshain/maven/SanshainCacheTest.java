package com.sanshain.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class SanshainCacheTest {

    @TempDir
    File tempDir;

    @Test
    public void testRequireEntryRoundTrip() throws IOException {
        SanshainCache cache = new SanshainCache(tempDir);
        String key = SanshainCache.requireKey("user-service", "1.2.0", "GET", "/api/v1/users", null);
        assertNull(cache.getRequireEntry(key));

        cache.updateRequireEntry(key, "\"sha256:def456\"");
        cache.save();

        SanshainCache cache2 = new SanshainCache(tempDir);
        SanshainCache.RequireEntry entry = cache2.getRequireEntry(key);
        assertNotNull(entry);
        assertEquals("\"sha256:def456\"", entry.etag);
        assertNotNull(entry.lastFetched);
    }

    @Test
    public void testRequireKeyFormats() {
        assertEquals("svc|1.2.0|GET|/api", SanshainCache.requireKey("svc", "1.2.0", "GET", "/api", null));
        assertEquals("svc|1.2.0|bundle", SanshainCache.requireBundleKey("svc", "1.2.0", null));
    }

    @Test
    public void testCorruptedCacheStartsFresh() throws IOException {
        File cacheFile = new File(tempDir, ".sanshain-cache.json");
        Files.writeString(cacheFile.toPath(), "not valid json{{{");

        SanshainCache cache = new SanshainCache(tempDir);
        assertNull(cache.getRequireEntry("anything"));
    }

    @Test
    public void testMissingCacheStartsFresh() {
        SanshainCache cache = new SanshainCache(new File(tempDir, "nonexistent"));
        assertNull(cache.getRequireEntry("anything"));
    }

    @Test
    public void testOldProvidesSectionIsHarmless() throws IOException {
        File cacheFile = new File(tempDir, ".sanshain-cache.json");
        Files.writeString(cacheFile.toPath(), "{\n"
                + "  \"provides\": {\"openapi.yaml\": {\"content_hash\": \"sha256:abc\", \"last_provided\": \"2026-01-01T00:00:00Z\"}},\n"
                + "  \"requires\": {\"svc|1.0.0|bundle\": {\"etag\": \"\\\"e1\\\"\", \"last_fetched\": \"2026-01-01T00:00:00Z\"}}\n"
                + "}");

        SanshainCache cache = new SanshainCache(tempDir);
        assertEquals("\"e1\"", cache.getRequireEntry("svc|1.0.0|bundle").etag);
        cache.updateRequireEntry("svc|2.0.0|bundle", "\"e2\"");
        cache.save();
        assertEquals("\"e2\"", new SanshainCache(tempDir).getRequireEntry("svc|2.0.0|bundle").etag);
    }

    @Test
    public void testFormatIsPartOfTheKey() {
        assertEquals("svc|1.2.0|SUB|orders|format=openapi", SanshainCache.requireKey("svc", "1.2.0", "SUB", "orders", "openapi"));
        assertEquals("svc|1.2.0|bundle|format=openapi", SanshainCache.requireBundleKey("svc", "1.2.0", "openapi"));
        assertNotEquals(SanshainCache.requireKey("svc", "1.2.0", "SUB", "orders", null),
                SanshainCache.requireKey("svc", "1.2.0", "SUB", "orders", "openapi"));
    }
}
