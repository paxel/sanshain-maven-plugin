package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the local ETag cache for Sanshain require operations.
 * State file location: target/.sanshain-cache.json (deleted on mvn clean).
 *
 * <p>Provides are never cached: every provide uploads, and the service treats an identical
 * re-provide as a no-op. A {@code provides} section left by an older plugin is ignored.</p>
 */
public class SanshainCache {

    private static final String CACHE_FILE_NAME = ".sanshain-cache.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final File cacheFile;
    private final CacheState state;

    /**
     * Creates a cache instance for the given target directory.
     * @param targetDir the Maven target directory
     */
    public SanshainCache(File targetDir) {
        this.cacheFile = new File(targetDir, CACHE_FILE_NAME);
        this.state = load();
    }

    private CacheState load() {
        if (cacheFile.exists()) {
            try {
                return MAPPER.readValue(cacheFile, CacheState.class);
            } catch (IOException e) {
                // Corrupted cache — start fresh
                return new CacheState();
            }
        }
        return new CacheState();
    }

    /**
     * Persists the current state to disk.
     * @throws IOException if writing fails
     */
    public void save() throws IOException {
        cacheFile.getParentFile().mkdirs();
        MAPPER.writeValue(cacheFile, state);
    }

    /**
     * Gets the cached require entry for a require key.
     * @param key the require identifier (e.g. "user-service|1.2.0|GET|/api/v1/users")
     * @return the cached entry, or null if not found
     */
    public RequireEntry getRequireEntry(String key) {
        return state.requires.get(key);
    }

    /**
     * Updates the cached require entry after a successful require.
     * @param key  the require identifier
     * @param etag the ETag value from the response
     */
    public void updateRequireEntry(String key, String etag) {
        RequireEntry entry = new RequireEntry();
        entry.etag = etag;
        entry.lastFetched = Instant.now().toString();
        state.requires.put(key, entry);
    }

    /**
     * Builds a require cache key from the given parameters.
     * @param serviceName the service name
     * @param version     the pinned version
     * @param method      the HTTP method
     * @param path        the API path
     * @return the cache key
     */
    public static String requireKey(String serviceName, String version, String method, String path) {
        return serviceName + "|" + version + "|" + method + "|" + path;
    }

    /**
     * Builds a require-bundle cache key from the given parameters.
     * @param serviceName the service name
     * @param version     the pinned version
     * @return the cache key
     */
    public static String requireBundleKey(String serviceName, String version) {
        return serviceName + "|" + version + "|bundle";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class CacheState {
        @JsonProperty("requires")
        public Map<String, RequireEntry> requires = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequireEntry {
        @JsonProperty("etag")
        public String etag;
        @JsonProperty("last_fetched")
        public String lastFetched;
    }
}
