package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

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

    @JsonProperty("harvested_subscriptions")
    private List<HarvestedSubscription> harvestedSubscriptions;

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
     * Gets the AsyncAPI {@code subscribe} operations Sanshain harvested from this
     * document as consumer edges. Present only for AsyncAPI provides, and only
     * when the document declared any.
     *
     * @return the harvested subscriptions, never null
     */
    public List<HarvestedSubscription> getHarvestedSubscriptions() {
        return harvestedSubscriptions == null ? Collections.emptyList() : harvestedSubscriptions;
    }

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

    /**
     * One AsyncAPI {@code subscribe} operation harvested from a provide as a
     * version-less consumer edge.
     *
     * <p>Each is checked against the publishing Producer's GA channel contract.
     * Reading fewer fields than the contract offers is fine; expecting a field
     * the contract does not guarantee is drift, reported here on a snapshot and
     * refused outright on a GA provide.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HarvestedSubscription {
        @JsonProperty("channel")
        public String channel;
        @JsonProperty("message_name")
        public String messageName;
        /** The Producer owning the channel's PUB contract, or null when none does yet. */
        @JsonProperty("owner")
        public String owner;
        /** Advisory note when the expectation is not satisfiable by the current contract. */
        @JsonProperty("drift")
        public String drift;

        /**
         * Whether this subscription carries an advisory worth a build warning —
         * either drift against the contract, or no GA publisher at all.
         *
         * @return true if the line deserves a warning rather than an info line
         */
        public boolean isAdvisory() {
            return drift != null || owner == null;
        }

        /**
         * Returns a one-line description for the build log.
         * @return the formatted line
         */
        public String describe() {
            StringBuilder line = new StringBuilder(channel == null ? "?" : channel);
            if (messageName != null) {
                line.append(" / ").append(messageName);
            }
            line.append(owner != null ? " ← " + owner : " ← (no publisher yet)");
            if (drift != null) {
                line.append(" — ").append(drift);
            }
            return line.toString();
        }
    }
}
