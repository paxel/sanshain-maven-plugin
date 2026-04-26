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

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(property = "configFile")
    private File configFile;

    @Parameter(property = "sanshain.url")
    private String sanshainUrl;

    @Parameter(property = "sanshain.token")
    private String token;

    @Parameter(property = "sanshain.serverId", defaultValue = "sanshain")
    private String serverId;

    @Parameter(property = "sanshain.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "sanshain.dry.run", defaultValue = "false")
    private boolean dryRun;

    @Parameter(property = "sanshain.strict", defaultValue = "false")
    private boolean strict;

    @Parameter(property = "sanshain.branch")
    private String branch;

    @Parameter(property = "sanshain.insecure")
    private Boolean insecure;

    @Parameter(property = "sanshain.compression")
    private Boolean compression;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    @Parameter(property = "sanshain.service.name")
    private String serviceName;

    @Parameter(property = "sanshain.timeout")
    private Integer timeout;

    @Parameter(property = "sanshain.require.skip", defaultValue = "false")
    private boolean skipRequire;

    private void initDefaults() {
        if (baseDir == null) {
            baseDir = new File(".");
        }
        if (configFile == null) {
            configFile = new File(baseDir, "sanshain.yaml");
        }
    }

    public void execute() throws MojoExecutionException {
        initDefaults();
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(getLog(), settings, baseDir, serverId, strict);

        if (skip || skipRequire) {
            getLog().info("Skipping sanshain:require (" + (skipRequire ? "sanshain.require.skip" : "sanshain.skip") + "=true)");
            return;
        }

        getLog().debug("Sanshain Maven Plugin v" + getClass().getPackage().getImplementationVersion());
        getLog().debug("Config file: " + configFile.getAbsolutePath() + " (exists: " + configFile.exists() + ")");

        SanshainConfig config = new SanshainConfig().loadConfig(configFile);

        String resolvedUrl = delegate.resolveUrl(sanshainUrl, config);
        String resolvedServiceName = resolveServiceName(config);
        
        if (resolvedServiceName == null) {
            delegate.abort("serviceName is required (either in pom.xml or sanshain.yaml)");
            return;
        }

        String resolvedToken = delegate.resolveToken(token);
        int globalTimeout = resolveGlobalTimeout(config);
        boolean resolvedCompression = delegate.resolveCompression(compression, config);
        boolean resolvedInsecure = delegate.resolveInsecure(insecure, config);
        String resolvedBranch = delegate.resolveBranch(branch);
        boolean bestEffort = config.getBestEffort() != null && config.getBestEffort();

        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        logResolvedValues(resolvedUrl, resolvedServiceName, resolvedCompression, resolvedInsecure, resolvedBranch, resolvedToken, globalTimeout);

        List<SanshainConfig.RequireConfig> requires = config.getRequires();
        if (requires == null || requires.isEmpty()) {
            delegate.abort("No requires configured in sanshain.yaml.");
            return;
        }

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
        SanshainCache cache = new SanshainCache(new File(baseDir, "target"));

        if (dryRun) {
            getLog().info("Dry-run mode enabled — endpoints will be validated but no dependencies recorded.");
        }

        for (SanshainConfig.RequireConfig req : requires) {
            processRequire(req, client, cache, resolvedUrl, resolvedToken, resolvedServiceName, resolvedBranch, globalTimeout, resolvedCompression, bestEffort);
        }
    }

    private String resolveServiceName(SanshainConfig config) {
        if (serviceName != null) return serviceName;
        return config.getClientName();
    }

    private int resolveGlobalTimeout(SanshainConfig config) {
        if (timeout != null) return timeout;
        if (config.getTimeout() != null) return config.getTimeout();
        return 120;
    }

    private void logResolvedValues(String url, String name, boolean comp, boolean ins, String br, String tok, int timeout) {
        getLog().debug("Resolved sanshainUrl: " + url);
        getLog().debug("Resolved serviceName: " + name);
        getLog().debug("Resolved compression: " + comp);
        getLog().debug("Resolved insecure: " + ins);
        getLog().debug("Resolved branch: " + br);
        getLog().debug("Resolved token: " + (tok != null ? "[set]" : "[not set]"));
        getLog().debug("Resolved global timeout: " + timeout + "s");
    }

    private void processRequire(SanshainConfig.RequireConfig req, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String defaultBranch, int globalTimeout, boolean compression, boolean bestEffort) throws MojoExecutionException {
        String reqServiceName = req.getServiceName();
        int reqTimeout = req.getTimeout() != null ? req.getTimeout() : globalTimeout;
        String apiType = req.getApiType();
        String reqBranch = req.getBranch() != null ? req.getBranch() : defaultBranch;

        File outputDirectory = resolveOutputDirectory(req);

        List<SanshainConfig.EndpointConfig> endpoints = req.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            getLog().warn("No endpoints defined for service: " + reqServiceName);
            return;
        }

        try {
            if (endpoints.size() >= 2) {
                requireBundle(req, client, cache, url, token, serviceName, reqBranch, reqTimeout, compression, apiType, outputDirectory);
            } else {
                requireSingle(req, endpoints.get(0), client, cache, url, token, serviceName, reqBranch, reqTimeout, compression, apiType, outputDirectory);
            }
        } catch (MojoExecutionException e) {
            if (bestEffort) {
                getLog().warn("Sanshain require failed for " + reqServiceName + " (best effort): " + e.getMessage());
            } else {
                throw e;
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write spec file for " + reqServiceName, e);
        }
    }

    private File resolveOutputDirectory(SanshainConfig.RequireConfig req) {
        String outputDir = req.getOutputDirectory();
        if (outputDir == null) {
            outputDir = "target/generated-sources/sanshain";
        }
        File dir = new File(baseDir, outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void requireBundle(SanshainConfig.RequireConfig req, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String branch, int timeout, boolean compression, String apiType, File outputDirectory) throws IOException, MojoExecutionException {
        String reqServiceName = req.getServiceName();
        getLog().info("Requiring bundle: " + reqServiceName + " (" + req.getEndpoints().size() + " endpoints, branch: " + branch + ")");

        String cacheKey = SanshainCache.requireBundleKey(reqServiceName, branch);
        SanshainCache.RequireEntry cachedEntry = cache.getRequireEntry(cacheKey);
        String cachedEtag = cachedEntry != null ? cachedEntry.etag : null;

        RequireResult result = client.postRequireBundleWithEtag(url, token, serviceName, reqServiceName, branch, req.getEndpoints(), timeout, compression, dryRun, apiType, cachedEtag);

        if (result.isNotModified()) {
            getLog().info("⏭ " + reqServiceName + " spec unchanged (304), skipping code generation.");
        } else {
            String fileName = reqServiceName + "_bundle." + ( "proto".equalsIgnoreCase(apiType) ? "proto" : "yaml");
            Path outputFile = outputDirectory.toPath().resolve(fileName);
            Files.writeString(outputFile, result.getContent());
            getLog().info("Saved bundle: " + outputFile);

            if (result.getEtag() != null) {
                cache.updateRequireEntry(cacheKey, result.getEtag());
                cache.save();
            }
        }
    }

    private void requireSingle(SanshainConfig.RequireConfig req, SanshainConfig.EndpointConfig endpoint, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String branch, int timeout, boolean compression, String apiType, File outputDirectory) throws IOException, MojoExecutionException {
        String reqServiceName = req.getServiceName();
        String method = endpoint.getMethod();
        String path = endpoint.getPath();

        getLog().info("Requiring: " + reqServiceName + " " + method + " " + path + " (branch: " + branch + ")");

        String cacheKey = SanshainCache.requireKey(reqServiceName, branch, method, path);
        SanshainCache.RequireEntry cachedEntry = cache.getRequireEntry(cacheKey);
        String cachedEtag = cachedEntry != null ? cachedEntry.etag : null;

        RequireResult result = client.getRequireWithEtag(url, token, serviceName, reqServiceName, branch, path, method, timeout, compression, dryRun, apiType, cachedEtag);

        if (result.isNotModified()) {
            getLog().info("⏭ " + reqServiceName + " spec unchanged (304), skipping code generation.");
        } else {
            String fileName = reqServiceName + "_" + path.replace("/", "_").replaceFirst("^_", "") + "_" + method + "." + ("proto".equalsIgnoreCase(apiType) ? "proto" : "yaml");
            Path outputFile = outputDirectory.toPath().resolve(fileName);
            Files.writeString(outputFile, result.getContent());
            getLog().info("Saved: " + outputFile);

            if (result.getEtag() != null) {
                cache.updateRequireEntry(cacheKey, result.getEtag());
                cache.save();
            }
        }
    }
}
