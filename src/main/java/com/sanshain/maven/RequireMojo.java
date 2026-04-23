package com.sanshain.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Goal which requires OpenAPI snippets from the Sanshain service.
 */
@Mojo(name = "require", defaultPhase = LifecyclePhase.GENERATE_SOURCES, requiresProject = false)
public class RequireMojo extends AbstractMojo {

    /** Creates a new instance of the require goal. */
    public RequireMojo() {}

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(property = "configFile")
    private File configFile;

    @Parameter(property = "sanshain.service.name")
    private String serviceName;

    @Parameter(property = "sanshain.url")
    private String sanshainUrl;

    @Parameter(property = "sanshain.token")
    private String token;

    @Parameter(property = "sanshain.timeout")
    private Integer timeout;

    @Parameter(property = "sanshain.compression")
    private Boolean compression;

    @Parameter(property = "sanshain.insecure")
    private Boolean insecure;

    @Parameter(property = "sanshain.serverId", defaultValue = "sanshain")
    private String serverId;

    @Parameter(property = "sanshain.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "sanshain.require.skip", defaultValue = "false")
    private boolean skipRequire;

    @Parameter(property = "sanshain.dry.run", defaultValue = "false")
    private boolean dryRun;

    @Parameter(property = "sanshain.branch")
    private String branch;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    public void execute() throws MojoExecutionException {
        if (baseDir == null) {
            baseDir = new File(".");
        }
        if (configFile == null) {
            configFile = new File(baseDir, "sanshain.yaml");
        }

        if (skip || skipRequire) {
            getLog().info("Skipping sanshain:require (" + (skipRequire ? "sanshain.require.skip" : "sanshain.skip") + "=true)");
            return;
        }

        getLog().debug("Sanshain Maven Plugin v" + getClass().getPackage().getImplementationVersion());
        getLog().debug("Config file: " + configFile.getAbsolutePath() + " (exists: " + configFile.exists() + ")");

        SanshainConfig config = new SanshainConfig().loadConfig(configFile);

        // Resolve sanshainUrl: maven property > settings.xml > yaml > default
        if (sanshainUrl == null) {
            String settingsUrl = resolveUrlFromSettings();
            if (settingsUrl != null) {
                sanshainUrl = settingsUrl;
            } else if (config.getSanshainUrl() != null) {
                sanshainUrl = config.getSanshainUrl();
            } else {
                sanshainUrl = "http://localhost:8080";
            }
        }

        // Resolve serviceName
        if (serviceName == null && config.getClientName() != null) {
            serviceName = config.getClientName();
        }
        if (serviceName == null) {
            throw new MojoExecutionException("serviceName is required (either in pom.xml or sanshain.yaml)");
        }

        // Resolve token
        String resolvedToken = resolveToken();
        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        // Resolve global timeout (default 120)
        int globalTimeout = resolveGlobalTimeout(config);

        // Resolve compression
        boolean resolvedCompression = resolveCompression(config);

        // Resolve insecure
        boolean resolvedInsecure = resolveInsecure(config);

        // Resolve branch: maven property > env > git > default
        if (branch == null) {
            branch = System.getenv("SANSHAIN_BRANCH");
        }
        if (branch == null) {
            branch = getGitBranch();
        }
        if (branch == null) {
            branch = "main";
        }

        getLog().debug("Resolved sanshainUrl: " + sanshainUrl);
        getLog().debug("Resolved serviceName: " + serviceName);
        getLog().debug("Resolved compression: " + resolvedCompression);
        getLog().debug("Resolved insecure: " + resolvedInsecure);
        getLog().debug("Resolved branch: " + branch);
        getLog().debug("Resolved token: " + (resolvedToken != null ? "[set]" : "[not set]"));
        getLog().debug("Resolved global timeout: " + globalTimeout + "s");

        // Get requires from config
        List<SanshainConfig.RequireConfig> requires = config.getRequires();
        if (requires == null || requires.isEmpty()) {
            throw new MojoExecutionException("requires are required in sanshain.yaml");
        }

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);

        if (dryRun) {
            getLog().info("Dry-run mode enabled — endpoints will be validated but no dependencies recorded.");
        }

        boolean bestEffort = config.getBestEffort() != null && config.getBestEffort();

        for (SanshainConfig.RequireConfig req : requires) {
            String reqServiceName = req.getServiceName();
            int reqTimeout = req.getTimeout() != null ? req.getTimeout() : globalTimeout;
            String apiType = req.getApiType();
            String reqBranch = req.getBranch() != null ? req.getBranch() : branch;

            String outputDir = req.getOutputDirectory();
            if (outputDir == null) {
                outputDir = "target/generated-sources/sanshain";
            }
            File outputDirectory = new File(baseDir, outputDir);
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }

            List<SanshainConfig.EndpointConfig> endpoints = req.getEndpoints();
            if (endpoints == null || endpoints.isEmpty()) {
                getLog().warn("No endpoints defined for service: " + reqServiceName);
                continue;
            }

            if (endpoints.size() >= 2) {
                // Use /require-bundle for multiple endpoints (deduplicated schemas)
                getLog().info("Requiring bundle: " + reqServiceName + " (" + endpoints.size() +
                        " endpoints, branch: " + reqBranch + ", timeout: " + reqTimeout + "s" +
                        (apiType != null ? ", type: " + apiType : "") + ")");

                try {
                    String yamlContent = client.postRequireBundle(sanshainUrl, resolvedToken, serviceName,
                            reqServiceName, reqBranch, endpoints, reqTimeout, resolvedCompression, dryRun, apiType);

                    String ext = "yaml";
                    if ("proto".equalsIgnoreCase(apiType)) ext = "proto";
                    String fileName = reqServiceName + "_bundle." + ext;
                    Path outputFile = outputDirectory.toPath().resolve(fileName);
                    Files.writeString(outputFile, yamlContent);
                    getLog().info("Saved bundle: " + outputFile);
                } catch (MojoExecutionException e) {
                    if (bestEffort) {
                        getLog().warn("Sanshain require-bundle failed for " + reqServiceName + " (best effort): " + e.getMessage());
                    } else {
                        throw e;
                    }
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to write bundle file for " + reqServiceName, e);
                }
            } else {
                // Single endpoint: use individual /require GET call
                SanshainConfig.EndpointConfig endpoint = endpoints.get(0);
                String method = endpoint.getMethod();
                String path = endpoint.getPath();

                getLog().info("Requiring: " + reqServiceName + " " + method + " " + path +
                        " (branch: " + reqBranch + ", timeout: " + reqTimeout + "s" +
                        (apiType != null ? ", type: " + apiType : "") + ")");

                try {
                    String yamlContent = client.getRequire(sanshainUrl, resolvedToken, serviceName,
                            reqServiceName, reqBranch, path, method, reqTimeout, resolvedCompression, dryRun, apiType);

                    String ext = "yaml";
                    if ("proto".equalsIgnoreCase(apiType)) ext = "proto";
                    String fileName = reqServiceName + "_" +
                            path.replace("/", "_").replaceFirst("^_", "") +
                            "_" + method + "." + ext;
                    Path outputFile = outputDirectory.toPath().resolve(fileName);
                    Files.writeString(outputFile, yamlContent);
                    getLog().info("Saved: " + outputFile);
                } catch (MojoExecutionException e) {
                    if (bestEffort) {
                        getLog().warn("Sanshain require failed for " + reqServiceName + " " + method + " " + path + " (best effort): " + e.getMessage());
                    } else {
                        throw e;
                    }
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to write file for " + reqServiceName + " " + path, e);
                }
            }
        }
    }

    private String resolveToken() {
        String envToken = System.getenv("SANSHAIN_TOKEN");
        if (envToken != null) return envToken;

        if (settings != null) {
            Server server = settings.getServer(serverId);
            if (server != null && server.getPassword() != null) {
                return server.getPassword();
            }
        }

        return token;
    }

    private String resolveUrlFromSettings() {
        if (settings == null) return null;
        Server server = settings.getServer(serverId);
        if (server == null) return null;
        return getServerConfigProperty(server, "sanshainUrl");
    }

    private String getServerConfigProperty(Server server, String property) {
        Object configuration = server.getConfiguration();
        if (configuration == null) return null;
        // Configuration is typically an Xpp3Dom; use reflection to avoid compile-time dependency
        try {
            java.lang.reflect.Method getChild = configuration.getClass().getMethod("getChild", String.class);
            Object child = getChild.invoke(configuration, property);
            if (child == null) return null;
            java.lang.reflect.Method getValue = child.getClass().getMethod("getValue");
            Object value = getValue.invoke(child);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int resolveGlobalTimeout(SanshainConfig config) {
        if (timeout != null) return timeout;
        if (config.getTimeout() != null) return config.getTimeout();
        return 120;
    }

    private boolean resolveCompression(SanshainConfig config) {
        if (compression != null) return compression;
        if (config.getCompression() != null) return config.getCompression();
        return true;
    }

    private boolean resolveInsecure(SanshainConfig config) {
        if (insecure != null) return insecure;
        if (config.getInsecure() != null) return config.getInsecure();
        return false;
    }

    private String getGitBranch() {
        // Check CI environment variables first
        String ciBranch = detectBranchFromCIEnvironment();
        if (ciBranch != null) {
            getLog().debug("Detected branch from CI environment: " + ciBranch);
            return ciBranch;
        }

        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            try (Repository repository = builder.readEnvironment()
                    .findGitDir(baseDir)
                    .build()) {
                if (repository != null) {
                    String branch = repository.getBranch();
                    // getBranch() returns a SHA when HEAD is detached (common in CI)
                    if (branch != null && branch.matches("[0-9a-f]{40}")) {
                        getLog().debug("HEAD is detached at " + branch + ", resolving branch from refs");
                        String resolved = resolveBranchFromDetachedHead(repository, ObjectId.fromString(branch));
                        if (resolved != null) {
                            getLog().debug("Resolved detached HEAD to branch: " + resolved);
                            return resolved;
                        }
                        getLog().debug("Could not resolve detached HEAD to a branch name via refs, trying git CLI");
                        String cliBranch = resolveBranchFromGitCli();
                        if (cliBranch != null) {
                            getLog().debug("Resolved detached HEAD to branch via git CLI: " + cliBranch);
                            return cliBranch;
                        }
                        return null;
                    }
                    return branch;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            getLog().debug("Could not determine git branch: " + e.getMessage());
        }
        return null;
    }

    private String detectBranchFromCIEnvironment() {
        // GitHub Actions
        String ref = System.getenv("GITHUB_HEAD_REF");
        if (ref != null && !ref.isEmpty()) return ref;
        ref = System.getenv("GITHUB_REF_NAME");
        if (ref != null && !ref.isEmpty()) return ref;

        // GitLab CI
        ref = System.getenv("CI_COMMIT_BRANCH");
        if (ref != null && !ref.isEmpty()) return ref;
        ref = System.getenv("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME");
        if (ref != null && !ref.isEmpty()) return ref;

        // Jenkins
        ref = System.getenv("GIT_BRANCH");
        if (ref != null && !ref.isEmpty()) {
            // Jenkins often prefixes with "origin/"
            if (ref.startsWith("origin/")) return ref.substring("origin/".length());
            return ref;
        }
        ref = System.getenv("BRANCH_NAME");
        if (ref != null && !ref.isEmpty()) return ref;

        // Bitbucket Pipelines
        ref = System.getenv("BITBUCKET_BRANCH");
        if (ref != null && !ref.isEmpty()) return ref;

        // Azure DevOps
        ref = System.getenv("BUILD_SOURCEBRANCH");
        if (ref != null && !ref.isEmpty()) {
            if (ref.startsWith("refs/heads/")) return ref.substring("refs/heads/".length());
            return ref;
        }

        // Travis CI
        ref = System.getenv("TRAVIS_BRANCH");
        if (ref != null && !ref.isEmpty()) return ref;

        // CircleCI
        ref = System.getenv("CIRCLE_BRANCH");
        if (ref != null && !ref.isEmpty()) return ref;

        return null;
    }

    private String resolveBranchFromGitCli() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "-a", "--contains", "HEAD");
            pb.directory(baseDir);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            for (String rawLine : output.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("(") || line.startsWith("* (")) continue;
                if (line.startsWith("* ")) line = line.substring(2);
                if (line.startsWith("remotes/origin/")) {
                    String candidate = line.substring("remotes/origin/".length());
                    if (!"HEAD".equals(candidate)) return candidate;
                    continue;
                }
                return line;
            }
        } catch (Exception e) {
            getLog().debug("git CLI branch resolution failed: " + e.getMessage());
        }
        return null;
    }

    private String resolveBranchFromDetachedHead(Repository repository, ObjectId headId) throws IOException {
        Map<String, Ref> refs = repository.getRefDatabase().getRefs("refs/heads/");
        for (Map.Entry<String, Ref> entry : refs.entrySet()) {
            Ref ref = entry.getValue();
            ObjectId refId = ref.getObjectId();
            if (refId != null && refId.equals(headId)) {
                return entry.getKey();
            }
        }
        // Also check remote tracking branches
        Map<String, Ref> remoteRefs = repository.getRefDatabase().getRefs("refs/remotes/origin/");
        for (Map.Entry<String, Ref> entry : remoteRefs.entrySet()) {
            Ref ref = entry.getValue();
            ObjectId refId = ref.getObjectId();
            if (refId != null && refId.equals(headId)) {
                String name = entry.getKey();
                if (!"HEAD".equals(name)) {
                    return name;
                }
            }
        }
        return null;
    }
}
