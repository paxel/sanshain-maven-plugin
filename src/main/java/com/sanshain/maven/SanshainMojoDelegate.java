package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    public SanshainMojoDelegate(Log log, Settings settings, File baseDir, String serverId, boolean strict) {
        this.log = log;
        this.settings = settings;
        this.baseDir = baseDir != null ? baseDir : new File(".");
        this.serverId = serverId != null ? serverId : "sanshain";
        this.strict = strict;
    }

    public void abort(String message) throws MojoExecutionException {
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

    public boolean resolveForce(boolean force) {
        if (force) return true;
        String envForce = environmentVariables.get("SANSHAIN_FORCE");
        if (envForce != null) return Boolean.parseBoolean(envForce);
        return false;
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
