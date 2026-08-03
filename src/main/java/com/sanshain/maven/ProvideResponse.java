package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body from the Sanshain provide endpoints (202 Accepted).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProvideResponse {

    @JsonProperty("version")
    private String version;

    @JsonProperty("stability")
    private String stability;

    @JsonProperty("content_hash")
    private String contentHash;

    @JsonProperty("changes")
    private Changes changes;

    /** Creates a new default instance. */
    public ProvideResponse() {}

    /** Gets the version read from the provided document. @return the version */
    public String getVersion() { return version; }

    /** Gets the declared stability the version was stored under. @return the stability */
    public String getStability() { return stability; }

    /** Gets the content hash. @return the content hash */
    public String getContentHash() { return contentHash; }

    /** Gets the changes summary. @return the changes */
    public Changes getChanges() { return changes; }

    /**
     * Returns a human-readable summary of the provide result.
     * @return formatted summary string
     */
    public String toSummary() {
        int inserts = changes != null ? changes.inserts : 0;
        int updates = changes != null ? changes.updates : 0;
        int deletes = changes != null ? changes.deletes : 0;
        return "✓ Provided to Sanshain as " + version + " (" + stability + "): " +
                inserts + " new, " + updates + " updated, " + deletes + " deleted endpoints";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Changes {
        @JsonProperty("inserts")
        public int inserts;
        @JsonProperty("updates")
        public int updates;
        @JsonProperty("deletes")
        public int deletes;
    }
}
