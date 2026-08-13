package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What retiring an API family actually shed — the 202 body of a provide call
 * carrying {@code retired: true}.
 *
 * <p>A retire publishes no version, so it reports none. All three counts are
 * zero for a dry run, which checks permission and stops.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetiredProtocol {

    @JsonProperty("tag_cleared")
    private String tagCleared;

    @JsonProperty("trunk_pins_closed")
    private int trunkPinsClosed;

    @JsonProperty("contracts_released")
    private int contractsReleased;

    /** Creates a new default instance. */
    public RetiredProtocol() {}

    /**
     * Gets the capability tag removed — {@code messaging} or {@code grpc}.
     * @return the tag, or null for OpenAPI and for a family that had none
     */
    public String getTagCleared() { return tagCleared; }

    /**
     * Gets how many open trunk pins were closed. They leave the current main
     * graph; their closed rows stay as timeline history.
     * @return the number of trunk pins closed
     */
    public int getTrunkPinsClosed() { return trunkPinsClosed; }

    /**
     * Gets how many AsyncAPI channel-message contracts were released for another
     * Producer to claim.
     * @return the number of contracts released
     */
    public int getContractsReleased() { return contractsReleased; }

    /**
     * Returns a human-readable summary of what the retire shed.
     * @return formatted summary string
     */
    public String toSummary() {
        StringBuilder summary = new StringBuilder("✓ Retired with Sanshain");
        if (tagCleared != null) {
            summary.append(": cleared the '").append(tagCleared).append("' capability");
        } else {
            summary.append(": capability withdrawn");
        }
        summary.append(", closed ").append(trunkPinsClosed).append(" trunk pin(s)");
        if (contractsReleased > 0) {
            summary.append(", released ").append(contractsReleased).append(" channel contract(s)");
        }
        summary.append(". Version history and existing Consumer pins are untouched.");
        return summary.toString();
    }
}
