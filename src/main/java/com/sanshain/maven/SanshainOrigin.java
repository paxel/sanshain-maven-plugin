package com.sanshain.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The origin of a build: what the pipeline knows about why this provide runs — where the
 * build ran, the pull or merge request behind it, the tickets it closes and one line of intent.
 * Sanshain stores it with the version (the latest provide that carries any of it wins; a
 * byte-identical re-provide only fills an empty one) and shows it on the version row, in the
 * diff and the blame.
 *
 * <p>Resolved like stability and stream: an explicit Maven property first, then the
 * {@code SANSHAIN_*} environment, and for the build and change URL the runner's own variables
 * on GitHub Actions, GitLab CI and Jenkins, so every pipeline carries them without editing a
 * job. Tickets and the note are human intent and come only from the property or the
 * {@code SANSHAIN_*} variable. Nothing is read from git.
 */
public final class SanshainOrigin {

    private static final SanshainOrigin NONE = new SanshainOrigin(null, null, Collections.emptyList(), null);

    private final String buildUrl;
    private final String changeUrl;
    private final List<String> tickets;
    private final String note;

    private SanshainOrigin(String buildUrl, String changeUrl, List<String> tickets, String note) {
        this.buildUrl = buildUrl;
        this.changeUrl = changeUrl;
        this.tickets = Collections.unmodifiableList(new ArrayList<>(tickets));
        this.note = note;
    }

    /** An origin that says nothing. */
    public static SanshainOrigin none() {
        return NONE;
    }

    /**
     * Resolves the origin of this build.
     *
     * @param buildUrl  the {@code sanshain.buildUrl} property, or null
     * @param changeUrl the {@code sanshain.changeUrl} property, or null
     * @param tickets   the {@code sanshain.tickets} property (comma-separated), or null
     * @param note      the {@code sanshain.note} property, or null
     * @param env       the environment variables of the build
     * @return the resolved origin, {@link #none()} when nothing is known
     */
    public static SanshainOrigin resolve(String buildUrl, String changeUrl, String tickets, String note,
                                         Map<String, String> env) {
        String resolvedBuild = first(buildUrl, env.get("SANSHAIN_BUILD_URL"), runnerBuildUrl(env));
        String resolvedChange = first(changeUrl, env.get("SANSHAIN_CHANGE_URL"), runnerChangeUrl(env));
        String resolvedTickets = first(tickets, env.get("SANSHAIN_TICKETS"));
        String resolvedNote = first(note, env.get("SANSHAIN_NOTE"));
        List<String> ids = new ArrayList<>();
        if (resolvedTickets != null) {
            for (String raw : resolvedTickets.split(",")) {
                String id = raw.trim();
                if (!id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        if (resolvedBuild == null && resolvedChange == null && ids.isEmpty() && resolvedNote == null) {
            return NONE;
        }
        return new SanshainOrigin(resolvedBuild, resolvedChange, ids, resolvedNote);
    }

    /** The first value that is neither null nor blank, trimmed; null when there is none. */
    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * The URL of this build on the runner: GitHub Actions ({@code GITHUB_SERVER_URL},
     * {@code GITHUB_REPOSITORY}, {@code GITHUB_RUN_ID}), GitLab CI ({@code CI_JOB_URL}) or
     * Jenkins ({@code BUILD_URL}); null elsewhere.
     */
    static String runnerBuildUrl(Map<String, String> env) {
        String server = env.get("GITHUB_SERVER_URL");
        String repository = env.get("GITHUB_REPOSITORY");
        String runId = env.get("GITHUB_RUN_ID");
        if (present(server) && present(repository) && present(runId)) {
            return server.trim() + "/" + repository.trim() + "/actions/runs/" + runId.trim();
        }
        return first(env.get("CI_JOB_URL"), env.get("BUILD_URL"));
    }

    /**
     * The URL of the change this build belongs to: a GitHub pull request (from
     * {@code GITHUB_REF} of the form {@code refs/pull/N/merge}), a GitLab merge request
     * ({@code CI_MERGE_REQUEST_PROJECT_URL} and {@code CI_MERGE_REQUEST_IID}) or Jenkins'
     * {@code CHANGE_URL}; null for a build that belongs to no change.
     */
    static String runnerChangeUrl(Map<String, String> env) {
        String server = env.get("GITHUB_SERVER_URL");
        String repository = env.get("GITHUB_REPOSITORY");
        String ref = env.get("GITHUB_REF");
        if (present(server) && present(repository) && present(ref) && ref.trim().startsWith("refs/pull/")) {
            String[] parts = ref.trim().split("/");
            if (parts.length >= 3 && !parts[2].isEmpty()) {
                return server.trim() + "/" + repository.trim() + "/pull/" + parts[2];
            }
        }
        String project = env.get("CI_MERGE_REQUEST_PROJECT_URL");
        String iid = env.get("CI_MERGE_REQUEST_IID");
        if (present(project) && present(iid)) {
            return project.trim() + "/-/merge_requests/" + iid.trim();
        }
        return first(env.get("CHANGE_URL"));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** Whether this origin says nothing at all. */
    public boolean isNone() {
        return buildUrl == null && changeUrl == null && tickets.isEmpty() && note == null;
    }

    public String getBuildUrl() {
        return buildUrl;
    }

    public String getChangeUrl() {
        return changeUrl;
    }

    public List<String> getTickets() {
        return tickets;
    }

    public String getNote() {
        return note;
    }

    /**
     * Adds the origin's fields to a provide payload, only those that are set: the server
     * refuses explicit nulls on a payload that denies unknown shapes, and an absent field is an
     * absent fact.
     *
     * @param payload the payload's extra fields
     */
    public void addTo(Map<String, Object> payload) {
        if (buildUrl != null) {
            payload.put("build_url", buildUrl);
        }
        if (changeUrl != null) {
            payload.put("change_url", changeUrl);
        }
        if (!tickets.isEmpty()) {
            payload.put("tickets", tickets);
        }
        if (note != null) {
            payload.put("note", note);
        }
    }

    @Override
    public String toString() {
        if (isNone()) {
            return "origin: none";
        }
        return "origin: build=" + buildUrl + ", change=" + changeUrl + ", tickets=" + tickets + ", note=" + note;
    }
}
