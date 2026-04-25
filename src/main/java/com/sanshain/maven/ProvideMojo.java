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
import java.util.Map;

/**
 * Goal which provides an OpenAPI specification to the Sanshain service.
 */
@Mojo(name = "provide", defaultPhase = LifecyclePhase.INITIALIZE, requiresProject = false)
public class ProvideMojo extends AbstractMojo {

    /** Creates a new instance of the provide goal. */
    public ProvideMojo() {}

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(property = "configFile")
    private File configFile;

    @Parameter(property = "sanshain.openapi.file")
    private File openApiFile;

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

    @Parameter(property = "sanshain.provide.skip", defaultValue = "false")
    private boolean skipProvide;

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
        if (openApiFile == null) {
            openApiFile = new File(new File(baseDir, "target"), "openapi.yaml");
        }

        if (skip || skipProvide) {
            getLog().info("Skipping sanshain:provide (" + (skipProvide ? "sanshain.provide.skip" : "sanshain.skip") + "=true)");
            return;
        }

        getLog().debug("Sanshain Maven Plugin v" + getClass().getPackage().getImplementationVersion());
        getLog().debug("Config file: " + configFile.getAbsolutePath() + " (exists: " + configFile.exists() + ")");

        SanshainConfig config = new SanshainConfig().loadConfig(configFile);

        // Resolve serviceName: maven property > yaml top-level > yaml provide.serviceName
        if (serviceName == null) {
            serviceName = config.getServiceName();
        }
        if (serviceName == null && config.getProvide() != null) {
            // serviceName = config.getProvide().getServiceName(); // Removed getServiceName from ProvideConfig in previous step, but it was there. 
            // Actually I should have kept it for compatibility or just use top-level.
        }

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

        // Resolve provide config
        File asyncApiFile = null;
        File protoFile = null;
        boolean hasSpecificOpenApiFile = false;
        if (config.getProvide() != null) {
            SanshainConfig.ProvideConfig provideConfig = config.getProvide();
            if (provideConfig.getOpenApiFile() != null) {
                openApiFile = new File(baseDir, provideConfig.getOpenApiFile());
                hasSpecificOpenApiFile = true;
            }
            if (provideConfig.getAsyncApiFile() != null) {
                asyncApiFile = new File(baseDir, provideConfig.getAsyncApiFile());
            }
            if (provideConfig.getProtoFile() != null) {
                protoFile = new File(baseDir, provideConfig.getProtoFile());
            }
        }

        // Resolve token: env > settings.xml > maven property
        String resolvedToken = resolveToken();
        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        // Resolve compression
        boolean resolvedCompression = resolveCompression(config);

        // Resolve insecure
        boolean resolvedInsecure = resolveInsecure(config);

        // Resolve branch: maven property > env > git > default
        String defaultBranch = branch;
        if (defaultBranch == null) {
            defaultBranch = System.getenv("SANSHAIN_BRANCH");
        }
        if (defaultBranch == null) {
            defaultBranch = getGitBranch();
        }
        if (defaultBranch == null) {
            defaultBranch = "main";
        }

        if (serviceName == null) {
            throw new MojoExecutionException("serviceName is required (either in pom.xml or sanshain.yaml)");
        }

        getLog().debug("Resolved sanshainUrl: " + sanshainUrl);
        getLog().debug("Resolved serviceName: " + serviceName);
        getLog().debug("Resolved compression: " + resolvedCompression);
        getLog().debug("Resolved insecure: " + resolvedInsecure);
        getLog().debug("Resolved branch: " + defaultBranch);
        getLog().debug("Resolved token: " + (resolvedToken != null ? "[set]" : "[not set]"));
        getLog().debug("Best effort: " + (config.getBestEffort() != null && config.getBestEffort()));

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
        SanshainCache cache = new SanshainCache(new File(baseDir, "target"));
        if (dryRun) {
            getLog().info("Dry-run mode enabled — spec will be validated but not stored.");
        }

        boolean bestEffort = config.getBestEffort() != null && config.getBestEffort();

        try {
            boolean providedAnything = false;

            // Handle 'provides' list
            if (config.getProvides() != null && !config.getProvides().isEmpty()) {
                for (SanshainConfig.ProvideConfig p : config.getProvides()) {
                    String b = p.getBranch() != null ? p.getBranch() : defaultBranch;
                    if (p.getFile() != null) {
                        provideFile(client, sanshainUrl, resolvedToken, serviceName, b, new File(baseDir, p.getFile()), p.getApiType(), resolvedCompression, dryRun, p.getBaseVersion(), cache);
                        providedAnything = true;
                    }
                }
            }

            // Handle single 'provide' (new format or backward compatibility)
            if (config.getProvide() != null) {
                SanshainConfig.ProvideConfig p = config.getProvide();
                String b = p.getBranch() != null ? p.getBranch() : defaultBranch;
                
                if (p.getFile() != null) {
                    provideFile(client, sanshainUrl, resolvedToken, serviceName, b, new File(baseDir, p.getFile()), p.getApiType(), resolvedCompression, dryRun, p.getBaseVersion(), cache);
                    providedAnything = true;
                }
                
                // Backward compatibility with openApiFile, asyncApiFile, protoFile
                if (p.getOpenApiFile() != null) {
                    provideFile(client, sanshainUrl, resolvedToken, serviceName, b, new File(baseDir, p.getOpenApiFile()), "openapi", resolvedCompression, dryRun, p.getBaseVersion(), cache);
                    providedAnything = true;
                }
                if (p.getAsyncApiFile() != null) {
                    provideFile(client, sanshainUrl, resolvedToken, serviceName, b, new File(baseDir, p.getAsyncApiFile()), "asyncapi", resolvedCompression, dryRun, p.getBaseVersion(), cache);
                    providedAnything = true;
                }
                if (p.getProtoFile() != null) {
                    provideFile(client, sanshainUrl, resolvedToken, serviceName, b, new File(baseDir, p.getProtoFile()), "proto", resolvedCompression, dryRun, p.getBaseVersion(), cache);
                    providedAnything = true;
                }
            }

            // Fallback to default openApiFile if nothing else provided and file exists
            if (!providedAnything && openApiFile != null && openApiFile.exists()) {
                provideFile(client, sanshainUrl, resolvedToken, serviceName, defaultBranch, openApiFile, "openapi", resolvedCompression, dryRun, null, cache);
                providedAnything = true;
            }

            if (!providedAnything) {
                getLog().warn("No specification files found to provide.");
            }
        } catch (MojoExecutionException e) {
            if (bestEffort) {
                getLog().warn("Sanshain provide failed (best effort): " + e.getMessage());
            } else {
                throw e;
            }
        } catch (IOException e) {
            if (bestEffort) {
                getLog().warn("Failed to read specification file (best effort): " + e.getMessage());
            } else {
                throw new MojoExecutionException("Failed to read specification file", e);
            }
        } catch (Exception e) {
            if (bestEffort) {
                getLog().warn("Sanshain provide failed unexpectedly (best effort): " + e.getMessage());
            } else {
                throw new MojoExecutionException("Failed to provide specification", e);
            }
        }
    }

    private void provideFile(SanshainHttpClient client, String url, String token, String serviceName, String branch, File file, String apiType, boolean compression, boolean dryRun) throws IOException, MojoExecutionException {
        provideFile(client, url, token, serviceName, branch, file, apiType, compression, dryRun, null, null);
    }

    private void provideFile(SanshainHttpClient client, String url, String token, String serviceName, String branch, File file, String apiType, boolean compression, boolean dryRun, Integer baseVersion, SanshainCache cache) throws IOException, MojoExecutionException {
        if (!file.exists()) {
            throw new MojoExecutionException("Specification file does not exist: " + file.getAbsolutePath());
        }
        String content = Files.readString(file.toPath());
        String fileKey = file.getName();

        // Feature 3: Client-side content caching — skip if unchanged
        String contentHash = SanshainCache.computeHash(content);
        if (cache != null) {
            SanshainCache.ProvideEntry cached = cache.getProvideEntry(fileKey);
            if (cached != null && contentHash.equals(cached.contentHash)) {
                getLog().info("\u23ed Spec unchanged (hash match), skipping provide.");
                return;
            }
            // Feature 1: Use cached version as base_version if not explicitly set
            if (baseVersion == null && cached != null && cached.version > 0) {
                baseVersion = cached.version;
            }
        }

        ProvideResponse response;
        if (apiType == null || apiType.equalsIgnoreCase("openapi")) {
            getLog().info("Providing OpenAPI: " + serviceName + " (branch: " + branch + ")");
            response = client.postProvide(url, token, serviceName, branch, content, compression, dryRun, baseVersion);
        } else if (apiType.equalsIgnoreCase("asyncapi")) {
            getLog().info("Providing AsyncAPI: " + serviceName + " (branch: " + branch + ")");
            response = client.postProvideAsyncApi(url, token, serviceName, branch, content, compression, dryRun, baseVersion);
        } else if (apiType.equalsIgnoreCase("proto") || apiType.equalsIgnoreCase("grpc")) {
            getLog().info("Providing Protocol Buffers: " + serviceName + " (branch: " + branch + ")");
            response = client.postProvideProto(url, token, serviceName, branch, content, compression, dryRun, baseVersion);
        } else {
            throw new MojoExecutionException("Unsupported apiType: " + apiType);
        }

        // Feature 2: Save version and content_hash from response
        if (cache != null && response != null) {
            String responseHash = response.getContentHash() != null ? response.getContentHash() : contentHash;
            cache.updateProvideEntry(fileKey, responseHash, response.getVersion());
            cache.save();
        }
    }

    private String resolveToken() {
        // 1. Env variable (highest priority)
        String envToken = System.getenv("SANSHAIN_TOKEN");
        if (envToken != null) return envToken;

        // 2. settings.xml
        if (settings != null) {
            Server server = settings.getServer(serverId);
            if (server != null && server.getPassword() != null) {
                return server.getPassword();
            }
        }

        // 3. Maven property
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

    private boolean resolveCompression(SanshainConfig config) {
        // Maven property overrides yaml, env overrides both (handled in ConfigLoader)
        if (compression != null) return compression;
        if (config.getCompression() != null) return config.getCompression();
        return true; // default
    }

    private boolean resolveInsecure(SanshainConfig config) {
        if (insecure != null) return insecure;
        if (config.getInsecure() != null) return config.getInsecure();
        return false; // default
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
