package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shared logic for Sanshain Mojos using composition.
 */
public class SanshainMojoDelegate {

    private final Log log;
    private final Settings settings;
    private final File baseDir;
    private final String serverId;
    private final boolean strict;
    private Map<String, String> environmentVariables = System.getenv();
    private boolean sourceProtectedBranchResolved;
    private String memoizedSourceProtectedBranch;

    public SanshainMojoDelegate(Log log, Settings settings, File baseDir, String serverId, boolean strict) {
        this.log = log;
        this.settings = settings;
        this.baseDir = baseDir != null ? baseDir : new File(".");
        this.serverId = serverId != null ? serverId : "sanshain";
        this.strict = strict;
    }

    public void abort(String message, boolean bestEffort) throws MojoExecutionException {
        if (bestEffort) {
            log.warn("Sanshain goal skipped (best effort): " + message);
            return;
        }
        if (strict) {
            throw new MojoExecutionException(message);
        }
        log.warn(message + " Skipping. Set sanshain.strict=true to fail in this case.");
    }

    public String resolveUrl(String sanshainUrl, SanshainConfig config) {
        if (sanshainUrl != null) return sanshainUrl;
        
        String settingsUrl = resolveUrlFromSettings();
        if (settingsUrl != null) return settingsUrl;
        
        if (config.getSanshainUrl() != null) return config.getSanshainUrl();
        
        return "http://localhost:8080";
    }

    private String resolveUrlFromSettings() {
        if (settings == null) return null;
        Server server = settings.getServer(serverId);
        if (server == null) return null;
        String url = getServerConfigProperty(server, "url");
        if (url == null) {
            url = getServerConfigProperty(server, "sanshainUrl");
        }
        return url;
    }

    public String resolveToken(String token) {
        String envToken = environmentVariables.get("SANSHAIN_TOKEN");
        if (envToken != null) return envToken;

        if (settings != null) {
            Server server = settings.getServer(serverId);
            if (server != null && server.getPassword() != null) {
                return server.getPassword();
            }
        }

        return token;
    }

    public boolean resolveCompression(Boolean compression, SanshainConfig config) {
        if (compression != null) return compression;
        if (config.getCompression() != null) return config.getCompression();
        return true;
    }

    public boolean resolveInsecure(Boolean insecure, SanshainConfig config) {
        if (insecure != null) return insecure;
        if (config.getInsecure() != null) return config.getInsecure();
        return false;
    }

    public boolean resolveBestEffort(Boolean bestEffort, SanshainConfig config) {
        if (bestEffort != null) return bestEffort;
        String envBestEffort = environmentVariables.get("SANSHAIN_BEST_EFFORT");
        if (envBestEffort != null) return Boolean.parseBoolean(envBestEffort);
        if (config != null && config.getBestEffort() != null) return config.getBestEffort();
        return false;
    }

    public boolean resolveCombine(Boolean combine, SanshainConfig config) {
        if (combine != null) return combine;
        String envCombine = environmentVariables.get("SANSHAIN_COMBINE");
        if (envCombine != null) return Boolean.parseBoolean(envCombine);
        if (config != null && config.getCombine() != null) return config.getCombine();
        return true;
    }

    public boolean resolveCombine(Boolean itemCombine, Boolean mojoCombine, SanshainConfig config) {
        if (itemCombine != null) return itemCombine;
        return resolveCombine(mojoCombine, config);
    }

    public void handleException(Exception e, boolean bestEffort) throws MojoExecutionException {
        if (bestEffort) {
            log.warn("Sanshain goal failed (best effort): " + e.getMessage());
        } else if (e instanceof MojoExecutionException) {
            throw (MojoExecutionException) e;
        } else {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    public boolean resolveForce(boolean force) {
        if (force) return true;
        String envForce = environmentVariables.get("SANSHAIN_FORCE");
        if (envForce != null) return Boolean.parseBoolean(envForce);
        return false;
    }

    public String resolvePullFromBranch(String pullFromBranch) {
        if (pullFromBranch != null) return pullFromBranch;
        return environmentVariables.get("SANSHAIN_PULL_FROM_BRANCH");
    }

    public String resolveSourceProtectedBranch(String sourceProtectedBranch) {
        if (sourceProtectedBranch != null) return sourceProtectedBranch;
        return environmentVariables.get("SANSHAIN_SOURCE_PROTECTED_BRANCH");
    }

    /**
     * Resolves the sticky lineage hint, falling back to git auto-detection when it isn't configured
     * explicitly. Call this at the point the value is actually needed, not up front: detection costs a
     * {@code GET /branches/protected} round-trip, and a build whose specs are all unchanged must stay
     * on the zero-request path that client-side content caching exists to provide. The result —
     * including a {@code null} one — is memoized for the lifetime of this delegate, so threading it
     * through several call sites still costs at most one request per Mojo execution.
     *
     * @param sourceProtectedBranch the explicitly configured value, if any (Maven property)
     * @param client                the HTTP client used to fetch protected branch patterns
     * @param url                   the resolved Sanshain service URL
     * @param token                 the resolved authentication token (may be null)
     * @return the resolved hint, or {@code null} if none was configured and none could be detected
     */
    public String resolveSourceProtectedBranch(String sourceProtectedBranch, SanshainHttpClient client, String url, String token) {
        if (sourceProtectedBranchResolved) {
            return memoizedSourceProtectedBranch;
        }
        sourceProtectedBranchResolved = true;

        String configured = resolveSourceProtectedBranch(sourceProtectedBranch);
        if (configured != null) {
            memoizedSourceProtectedBranch = configured;
        } else if (hasResolvableGitRepository()) {
            memoizedSourceProtectedBranch = detectSourceProtectedBranch(client.getProtectedBranches(url, token));
        }
        return memoizedSourceProtectedBranch;
    }

    /**
     * Whether a git repository can be resolved from {@code baseDir} at all. Used to skip the
     * {@code GET /branches/protected} call entirely when detection has no chance of succeeding.
     */
    public boolean hasResolvableGitRepository() {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            // Opened purely to see whether it resolves; closed immediately, nothing read from it.
            builder.readEnvironment().findGitDir(baseDir).build().close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Auto-detects {@code source_protected_branch} by matching the service's protected branch patterns
     * (item #17: see the sanshain-service issue "Per-branch fallback resolution") against locally known
     * branches, using a CI base-branch hint when available and JGit merge-base otherwise. Never throws —
     * any failure (shallow clone, unresolvable ref, no repository) results in a candidate being skipped
     * or, if nothing resolves at all, a {@code null} return so the field is simply omitted.
     *
     * @param protectedBranchPatterns the protected branch patterns reported by {@code GET /branches/protected}
     *                                (e.g. {@code master}, {@code release/*})
     * @return the best-matching protected branch, or {@code null} if none could be determined
     */
    public String detectSourceProtectedBranch(List<String> protectedBranchPatterns) {
        if (protectedBranchPatterns == null || protectedBranchPatterns.isEmpty()) {
            return null;
        }

        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            try (Repository repository = builder.readEnvironment().findGitDir(baseDir).build()) {
                Map<String, Ref> candidates = matchingCandidateRefs(repository, protectedBranchPatterns);
                if (candidates.isEmpty()) {
                    return null;
                }

                String ciHint = detectBaseBranchFromCIEnvironment();
                if (ciHint != null && candidates.containsKey(ciHint)) {
                    return ciHint;
                }

                return closestMergeBase(repository, candidates);
            }
        } catch (Exception e) {
            log.debug("Could not auto-detect sourceProtectedBranch: " + e.getMessage());
            return null;
        }
    }

    /**
     * Local branches take priority over remote-tracking branches with the same short name.
     */
    private Map<String, Ref> matchingCandidateRefs(Repository repository, List<String> patterns) throws IOException {
        Map<String, Ref> refsByShortName = new LinkedHashMap<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
            refsByShortName.put(shortBranchName(ref.getName()), ref);
        }
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES)) {
            refsByShortName.putIfAbsent(shortBranchName(ref.getName()), ref);
        }

        Map<String, Ref> matching = new LinkedHashMap<>();
        for (Map.Entry<String, Ref> entry : refsByShortName.entrySet()) {
            for (String pattern : patterns) {
                if (matchesPattern(pattern, entry.getKey())) {
                    matching.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
        return matching;
    }

    private String shortBranchName(String refName) {
        if (refName.startsWith(Constants.R_HEADS)) {
            return refName.substring(Constants.R_HEADS.length());
        }
        if (refName.startsWith(Constants.R_REMOTES)) {
            String rest = refName.substring(Constants.R_REMOTES.length());
            int slash = rest.indexOf('/');
            return slash >= 0 ? rest.substring(slash + 1) : rest;
        }
        return refName;
    }

    /**
     * Translates a server-side wildcard pattern (`*` = any sequence, `?` = exactly one character,
     * everything else literal) into a regex, mirroring the matcher sanshain-service uses for
     * protected-branch patterns.
     */
    private boolean matchesPattern(String pattern, String branchName) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return branchName.matches(regex.toString());
    }

    /**
     * Picks the candidate whose merge-base with HEAD is "closest": a candidate HEAD strictly descends
     * from (no divergence) beats one it has diverged from, then the most recent merge-base commit wins,
     * then alphabetical order — so the same input always resolves to the same answer, since this value
     * is persisted server-side write-once.
     */
    private String closestMergeBase(Repository repository, Map<String, Ref> candidates) {
        String best = null;
        RevCommit bestBase = null;
        boolean bestIsAncestor = false;

        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId headId = repository.resolve("HEAD");
            if (headId == null) return null;

            for (Map.Entry<String, Ref> entry : candidates.entrySet()) {
                ObjectId candidateId = entry.getValue().getObjectId();
                if (candidateId == null) continue;

                try {
                    walk.reset();
                    walk.setRevFilter(RevFilter.MERGE_BASE);
                    RevCommit headCommit = walk.parseCommit(headId);
                    RevCommit candidateCommit = walk.parseCommit(candidateId);
                    walk.markStart(headCommit);
                    walk.markStart(candidateCommit);
                    RevCommit base = walk.next();
                    if (base == null) continue;

                    boolean isAncestor = base.equals(candidateCommit);

                    if (best == null
                            || (isAncestor && !bestIsAncestor)
                            || (isAncestor == bestIsAncestor && base.getCommitTime() > bestBase.getCommitTime())
                            || (isAncestor == bestIsAncestor && base.getCommitTime() == bestBase.getCommitTime() && entry.getKey().compareTo(best) < 0)) {
                        best = entry.getKey();
                        bestBase = base;
                        bestIsAncestor = isAncestor;
                    }
                } catch (Exception e) {
                    // Unresolvable candidate (e.g. shallow clone missing history) — skip it.
                    log.debug("Skipping sourceProtectedBranch candidate '" + entry.getKey() + "': " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not compute merge-base for sourceProtectedBranch: " + e.getMessage());
            return null;
        }

        return best;
    }

    private String detectBaseBranchFromCIEnvironment() {
        String githubBaseRef = environmentVariables.get("GITHUB_BASE_REF");
        if (githubBaseRef != null && !githubBaseRef.isEmpty()) return githubBaseRef;
        String gitlabTargetBranch = environmentVariables.get("CI_MERGE_REQUEST_TARGET_BRANCH_NAME");
        if (gitlabTargetBranch != null && !gitlabTargetBranch.isEmpty()) return gitlabTargetBranch;
        String bitbucketDestBranch = environmentVariables.get("BITBUCKET_PR_DESTINATION_BRANCH");
        if (bitbucketDestBranch != null && !bitbucketDestBranch.isEmpty()) return bitbucketDestBranch;
        return null;
    }

    public String resolveBranch(String branch) {
        // 1. Explicit -Dsanshain.branch wins
        if (branch != null) return branch;

        // 2. CI environment variables (includes SANSHAIN_BRANCH)
        String ciBranch = detectBranchFromCIEnvironment();
        if (ciBranch != null) return ciBranch;

        // 3. JGit — only if it gives a real branch name (not detached HEAD)
        String jgitBranch = getJGitBranch();
        if (jgitBranch != null) return jgitBranch;

        // 4. git CLI as last resort
        String cliBranch = resolveBranchFromGitCli();
        if (cliBranch != null) return cliBranch;

        log.warn("Could not detect git branch from environment or repository.");
        return null;
    }

    private String getJGitBranch() {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            try (Repository repository = builder.readEnvironment().findGitDir(baseDir).build()) {
                String branchName = repository.getBranch();
                if (branchName != null && !branchName.isEmpty() && !ObjectId.isId(branchName)) {
                    return branchName;
                }
            }
        } catch (Exception e) {
            log.debug("Could not detect git branch via JGit: " + e.getMessage());
        }
        return null;
    }

    void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    private String detectBranchFromCIEnvironment() {
        Map<String, String> env = environmentVariables;
        if (env.containsKey("SANSHAIN_BRANCH")) return env.get("SANSHAIN_BRANCH");
        if (env.containsKey("GITHUB_REF_NAME")) return env.get("GITHUB_REF_NAME");
        if (env.containsKey("GIT_BRANCH")) return env.get("GIT_BRANCH");
        if (env.containsKey("CI_COMMIT_REF_NAME")) return env.get("CI_COMMIT_REF_NAME");
        if (env.containsKey("BITBUCKET_BRANCH")) return env.get("BITBUCKET_BRANCH");
        if (env.containsKey("BRANCH_NAME")) return env.get("BRANCH_NAME");
        return null;
    }

    private String resolveBranchFromGitCli() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .directory(baseDir)
                    .start();
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    if (line != null && !line.equals("HEAD")) return line;
                }
            }
        } catch (Exception e) {
            log.debug("Could not detect git branch via CLI: " + e.getMessage());
        }
        return null;
    }


    public String resolveServiceName(String serviceName, SanshainConfig config) {
        if (serviceName != null) return serviceName;
        String name = config.getServiceName();
        if (name == null) {
            name = config.getClientName();
        }
        return name;
    }

    private String getServerConfigProperty(Server server, String property) {
        if (server == null || server.getConfiguration() == null) return null;
        Object config = server.getConfiguration();
        if (config instanceof org.codehaus.plexus.util.xml.Xpp3Dom) {
            org.codehaus.plexus.util.xml.Xpp3Dom dom = (org.codehaus.plexus.util.xml.Xpp3Dom) config;
            org.codehaus.plexus.util.xml.Xpp3Dom child = dom.getChild(property);
            if (child != null) return child.getValue();
        }
        return null;
    }
}
