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
 * <p>Trunk and a tag are mutually exclusive, so this type cannot hold both. A build
 * declaring both is recorded in neither graph: it resolves to a stream that sends
 * nothing and carries {@link #MUTUALLY_EXCLUSIVE} as its {@link #getWarning() warning}.
 * The graph is informational, so this is a warning, not a failure (strict mode fails).
 */
public final class SanshainStream {

    /** The warning for a build that declares both trunk and a tag. */
    public static final String MUTUALLY_EXCLUSIVE =
            "trunk and tag are mutually exclusive - this build is recorded in neither";

    private static final SanshainStream NONE = new SanshainStream(false, null, null);
    private static final SanshainStream TRUNK = new SanshainStream(true, null, null);
    /** Both trunk and a tag were declared: sends neither, and says so. */
    private static final SanshainStream NEITHER = new SanshainStream(false, null, MUTUALLY_EXCLUSIVE);

    private final boolean trunk;
    private final String tag;
    private final String warning;

    private SanshainStream(boolean trunk, String tag, String warning) {
        this.trunk = trunk;
        this.tag = tag;
        this.warning = warning;
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
        return new SanshainStream(false, tag.trim(), null);
    }

    /**
     * Resolves the stream a build declared, preferring an explicit plugin
     * parameter over the environment. A build that claims both trunk and a tag
     * resolves to a stream that sends neither and carries a {@link #getWarning() warning}.
     *
     * @param trunkFlag    the {@code sanshain.trunk} parameter, or null if unset
     * @param tagValue     the {@code sanshain.tag} parameter, or null if unset
     * @param envTrunk     the {@code SANSHAIN_TRUNK} environment value, or null
     * @param envTag       the {@code SANSHAIN_TAG} environment value, or null
     * @return the resolved stream
     */
    public static SanshainStream resolve(Boolean trunkFlag, String tagValue, String envTrunk, String envTag) {
        boolean isTrunk = trunkFlag != null ? trunkFlag : SanshainMojoDelegate.isSwitchOn(envTrunk);
        String resolvedTag = tagValue != null && !tagValue.isBlank() ? tagValue : envTag;
        boolean hasTag = resolvedTag != null && !resolvedTag.isBlank();

        if (isTrunk && hasTag) {
            return NEITHER;
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
     * The warning resolving this stream produced: {@link #MUTUALLY_EXCLUSIVE} when the build
     * declared both trunk and a tag, otherwise null.
     * @return the warning, or null
     */
    public String getWarning() {
        return warning;
    }

    /**
     * Whether this build speaks for no graph.
     * @return true if neither trunk nor a tag is sent
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
