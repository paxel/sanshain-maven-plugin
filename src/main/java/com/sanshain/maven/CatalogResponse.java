package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * The periphery Sanshain recorded for a system — the 200 body of {@code PUT /catalog/<system>},
 * which may also say that nothing was recorded.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogResponse {

    @JsonProperty("system")
    private String system;

    @JsonProperty("trunk")
    private boolean trunk;

    @JsonProperty("resources")
    private List<String> resources = new ArrayList<>();

    @JsonProperty("recorded")
    private Boolean recorded;

    /** Creates a new default instance. */
    public CatalogResponse() {}

    /**
     * Gets the system the periphery was recorded for.
     * @return the system name
     */
    public String getSystem() { return system; }

    /**
     * Whether the push updated the main graph as well as the dev graph.
     * @return true for a trunk push
     */
    public boolean isTrunk() { return trunk; }

    /**
     * Gets the recorded refs, {@code resource:<namespace>/<name>}, lower-cased and sorted.
     * @return the recorded resources
     */
    public List<String> getResources() { return resources == null ? new ArrayList<>() : resources; }

    /**
     * Whether Sanshain recorded the periphery. False when it could not read the file (the previous
     * periphery stays; the reason arrives as a {@code Sanshain-Warning}). A response without the
     * field (an older 2.4.0 pre-release service) counts as recorded.
     * @return true unless the service said {@code recorded: false}
     */
    public boolean isRecorded() { return recorded == null || recorded; }

    /**
     * Returns the summary line: system, the graphs updated, and the resource count.
     * @return formatted summary line
     */
    public String toSummary() {
        return "Catalog recorded for " + system + " (" + (trunk ? "main+dev" : "dev") + "): "
                + getResources().size() + " resources";
    }
}
