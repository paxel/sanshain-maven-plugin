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
    public void testComputeHash() {
        String hash = SanshainCache.computeHash("hello");
        assertTrue(hash.startsWith("sha256:"));
        assertEquals(71, hash.length()); // "sha256:" + 64 hex chars
        // Same input → same hash
        assertEquals(hash, SanshainCache.computeHash("hello"));
        // Different input → different hash
        assertNotEquals(hash, SanshainCache.computeHash("world"));
    }

    @Test
    public void testProvideEntryRoundTrip() throws IOException {
        SanshainCache cache = new SanshainCache(tempDir);
        assertNull(cache.getProvideEntry("openapi.yaml"));

        cache.updateProvideEntry("openapi.yaml", "sha256:abc123", 5);
        cache.save();

        // Reload from disk
        SanshainCache cache2 = new SanshainCache(tempDir);
        SanshainCache.ProvideEntry entry = cache2.getProvideEntry("openapi.yaml");
        assertNotNull(entry);
        assertEquals("sha256:abc123", entry.contentHash);
        assertEquals(5, entry.version);
        assertNotNull(entry.lastProvided);
    }

    @Test
    public void testRequireEntryRoundTrip() throws IOException {
        SanshainCache cache = new SanshainCache(tempDir);
        String key = SanshainCache.requireKey("user-service", "main", "GET", "/api/v1/users");
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
        assertEquals("svc|main|GET|/api", SanshainCache.requireKey("svc", "main", "GET", "/api"));
        assertEquals("svc|main|bundle", SanshainCache.requireBundleKey("svc", "main"));
    }

    @Test
    public void testCorruptedCacheStartsFresh() throws IOException {
        File cacheFile = new File(tempDir, ".sanshain-cache.json");
        Files.writeString(cacheFile.toPath(), "not valid json{{{");

        SanshainCache cache = new SanshainCache(tempDir);
        assertNull(cache.getProvideEntry("anything"));
    }

    @Test
    public void testMissingCacheStartsFresh() {
        SanshainCache cache = new SanshainCache(new File(tempDir, "nonexistent"));
        assertNull(cache.getProvideEntry("anything"));
    }
}
