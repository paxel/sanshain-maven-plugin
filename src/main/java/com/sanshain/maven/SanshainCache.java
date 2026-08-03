package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the local state cache file for Sanshain provide/require operations.
 * State file location: target/.sanshain-cache.json (deleted on mvn clean).
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
     * Gets the cached provide entry for a spec file key.
     * @param key the spec file identifier (e.g. "openapi.yaml")
     * @return the cached entry, or null if not found
     */
    public ProvideEntry getProvideEntry(String key) {
        return state.provides.get(key);
    }

    /**
     * Updates the cached provide entry after a successful provide.
     * @param key         the spec file identifier
     * @param contentHash the SHA-256 hash of the content
     */
    public void updateProvideEntry(String key, String contentHash) {
        ProvideEntry entry = new ProvideEntry();
        entry.contentHash = contentHash;
        entry.lastProvided = Instant.now().toString();
        state.provides.put(key, entry);
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
     * Computes the SHA-256 hash of the given content.
     * @param content the content to hash
     * @return the hash in "sha256:hex" format
     */
    public static String computeHash(String content) {
        if (content == null) return "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // Empty string hash
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
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
        @JsonProperty("provides")
        public Map<String, ProvideEntry> provides = new LinkedHashMap<>();
        @JsonProperty("requires")
        public Map<String, RequireEntry> requires = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProvideEntry {
        @JsonProperty("content_hash")
        public String contentHash;
        @JsonProperty("last_provided")
        public String lastProvided;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequireEntry {
        @JsonProperty("etag")
        public String etag;
        @JsonProperty("last_fetched")
        public String lastFetched;
    }
}
