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
        String content = "hello world";
        String hash = SanshainCache.computeHash(content);
        assertEquals("sha256:b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }

    @Test
    public void testComputeHashEmpty() {
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SanshainCache.computeHash(""));
        assertEquals("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SanshainCache.computeHash(null));
    }

    @Test
    public void testProvideEntryRoundTrip() throws IOException {
        SanshainCache cache = new SanshainCache(tempDir);
        assertNull(cache.getProvideEntry("openapi.yaml"));

        cache.updateProvideEntry("openapi.yaml", "sha256:abc123");
        cache.save();

        // Reload from disk
        SanshainCache cache2 = new SanshainCache(tempDir);
        SanshainCache.ProvideEntry entry = cache2.getProvideEntry("openapi.yaml");
        assertNotNull(entry);
        assertEquals("sha256:abc123", entry.contentHash);
        assertNotNull(entry.lastProvided);
    }

    @Test
    public void testRequireEntryRoundTrip() throws IOException {
        SanshainCache cache = new SanshainCache(tempDir);
        String key = SanshainCache.requireKey("user-service", "1.2.0", "GET", "/api/v1/users");
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
        assertEquals("svc|1.2.0|GET|/api", SanshainCache.requireKey("svc", "1.2.0", "GET", "/api"));
        assertEquals("svc|1.2.0|bundle", SanshainCache.requireBundleKey("svc", "1.2.0"));
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
