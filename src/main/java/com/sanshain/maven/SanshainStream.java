package com.sanshain.maven;

/**
 * Which graph a build's provides and requires belong to: trunk, a named
 * sanshain-branch, or neither.
 *
 * <p>The stream is a property of the invocation, not of the repository, so it is
 * declared by the pipeline ({@code -Dsanshain.trunk=true} / {@code SANSHAIN_TRUNK},
 * {@code -Dsanshain.tag=<branch>} / {@code SANSHAIN_TAG}) rather than committed to
 * {@code sanshain.yaml}. A developer's laptop build and the trunk CI job run the
 * same configuration; only the pipeline knows which of them speaks for trunk.
 *
 * <p>Trunk and a tag are mutually exclusive — the server answers {@code 400} for a
 * request carrying both — so this type cannot hold both. Declaring both on one
 * build is a misconfiguration worth naming locally rather than a round trip.
 */
public final class SanshainStream {

    private static final SanshainStream NONE = new SanshainStream(false, null);
    private static final SanshainStream TRUNK = new SanshainStream(true, null);

    private final boolean trunk;
    private final String tag;

    private SanshainStream(boolean trunk, String tag) {
        this.trunk = trunk;
        this.tag = tag;
    }

    /**
     * The stream of a build that speaks for no graph — the default.
     * @return the empty stream
     */
    public static SanshainStream none() {
        return NONE;
    }

    /**
     * The trunk stream: this build's versions and pins maintain the main graph.
     * @return the trunk stream
     */
    public static SanshainStream trunk() {
        return TRUNK;
    }

    /**
     * A named sanshain-branch — a release or hotfix build updating that graph
     * instead of trunk.
     *
     * @param tag the branch name; blank or null yields {@link #none()}
     * @return the tagged stream
     */
    public static SanshainStream tag(String tag) {
        if (tag == null || tag.isBlank()) {
            return NONE;
        }
        return new SanshainStream(false, tag.trim());
    }

    /**
     * Resolves the stream a build declared, preferring an explicit plugin
     * parameter over the environment, and refusing a build that claims both.
     *
     * @param trunkFlag    the {@code sanshain.trunk} parameter, or null if unset
     * @param tagValue     the {@code sanshain.tag} parameter, or null if unset
     * @param envTrunk     the {@code SANSHAIN_TRUNK} environment value, or null
     * @param envTag       the {@code SANSHAIN_TAG} environment value, or null
     * @return the resolved stream
     * @throws IllegalArgumentException if the build declares both trunk and a tag
     */
    public static SanshainStream resolve(Boolean trunkFlag, String tagValue, String envTrunk, String envTag) {
        boolean isTrunk = trunkFlag != null ? trunkFlag : Boolean.parseBoolean(envTrunk);
        String resolvedTag = tagValue != null && !tagValue.isBlank() ? tagValue : envTag;
        boolean hasTag = resolvedTag != null && !resolvedTag.isBlank();

        if (isTrunk && hasTag) {
            throw new IllegalArgumentException(
                    "this build declares both trunk and tag '" + resolvedTag.trim() + "' — a build belongs to "
                            + "the trunk stream or to one sanshain-branch, never both. Set only one of "
                            + "sanshain.trunk / SANSHAIN_TRUNK and sanshain.tag / SANSHAIN_TAG.");
        }
        if (isTrunk) {
            return trunk();
        }
        return tag(resolvedTag);
    }

    /**
     * Whether this build speaks for the trunk stream.
     * @return true if this is the trunk stream
     */
    public boolean isTrunk() {
        return trunk;
    }

    /**
     * The sanshain-branch this build belongs to, if any.
     * @return the branch name, or null
     */
    public String getTag() {
        return tag;
    }

    /**
     * Whether this build declared any stream at all.
     * @return true if neither trunk nor a tag was declared
     */
    public boolean isNone() {
        return !trunk && tag == null;
    }

    /**
     * Renders the stream as require query parameters, ready to append.
     * @return the parameters, beginning with {@code &}, or an empty string
     */
    public String toQueryParams() {
        if (trunk) {
            return "&trunk=true";
        }
        if (tag != null) {
            return "&tag=" + SanshainHttpClient.urlEncode(tag);
        }
        return "";
    }

    /**
     * A short label for build logs.
     * @return the stream's name
     */
    @Override
    public String toString() {
        if (trunk) {
            return "trunk";
        }
        return tag != null ? "branch '" + tag + "'" : "none";
    }
}
