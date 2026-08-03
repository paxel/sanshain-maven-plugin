package com.sanshain.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Goal which requires endpoint snippets from the Sanshain service at exact pinned versions.
 * Resolution is immediate — a pinned version that does not exist fails fast (404); nothing waits.
 */
@Mojo(name = "require", defaultPhase = LifecyclePhase.INITIALIZE, requiresProject = false)
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

    @Parameter(property = "sanshain.insecure")
    private Boolean insecure;

    @Parameter(property = "sanshain.compression")
    private Boolean compression;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    @Parameter(property = "sanshain.service.name")
    private String serviceName;

    @Parameter(property = "sanshain.require.skip", defaultValue = "false")
    private boolean skipRequire;

    @Parameter(property = "sanshain.bestEffort")
    private Boolean bestEffort;

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
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(getLog(), settings, serverId, strict);

        if (skip || skipRequire) {
            getLog().info("Skipping sanshain:require (" + (skipRequire ? "sanshain.require.skip" : "sanshain.skip") + "=true)");
            return;
        }

        getLog().debug("Sanshain Maven Plugin v" + getClass().getPackage().getImplementationVersion());
        getLog().debug("Config file: " + configFile.getAbsolutePath() + " (exists: " + configFile.exists() + ")");

        // Early resolution of bestEffort to handle config loading errors
        boolean resolvedBestEffort = delegate.resolveBestEffort(bestEffort, null);

        SanshainConfig config;
        try {
            config = new SanshainConfig().loadConfig(configFile);
            // Re-resolve to incorporate potential YAML overrides
            resolvedBestEffort = delegate.resolveBestEffort(bestEffort, config);
        } catch (Exception e) {
            delegate.handleException(e, resolvedBestEffort);
            config = new SanshainConfig();
        }

        String resolvedUrl = delegate.resolveUrl(sanshainUrl, config);
        String resolvedServiceName = resolveServiceName(config);

        if (resolvedServiceName == null) {
            delegate.abort("serviceName is required (either in pom.xml or sanshain.yaml)", resolvedBestEffort);
            return;
        }

        String resolvedToken = delegate.resolveToken(token);
        boolean resolvedCompression = delegate.resolveCompression(compression, config);
        boolean resolvedInsecure = delegate.resolveInsecure(insecure, config);

        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        logResolvedValues(resolvedUrl, resolvedServiceName, resolvedCompression, resolvedInsecure, resolvedToken);

        List<SanshainConfig.RequireConfig> requires = config.getRequires();
        if (requires == null || requires.isEmpty()) {
            delegate.abort("No requires configured in sanshain.yaml.", resolvedBestEffort);
            return;
        }

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
        SanshainCache cache = new SanshainCache(new File(baseDir, "target"));

        if (dryRun) {
            getLog().info("Dry-run mode enabled — endpoints will be validated but no dependencies recorded.");
        }

        for (SanshainConfig.RequireConfig req : requires) {
            processRequire(req, client, cache, resolvedUrl, resolvedToken, resolvedServiceName, resolvedCompression, resolvedBestEffort, delegate);
        }
    }

    private String resolveServiceName(SanshainConfig config) {
        if (serviceName != null) return serviceName;
        return config.getClientName();
    }

    private void logResolvedValues(String url, String name, boolean comp, boolean ins, String tok) {
        getLog().debug("Resolved sanshainUrl: " + url);
        getLog().debug("Resolved serviceName: " + name);
        getLog().debug("Resolved compression: " + comp);
        getLog().debug("Resolved insecure: " + ins);
        getLog().debug("Resolved token: " + (tok != null ? "[set]" : "[not set]"));
    }

    private void processRequire(SanshainConfig.RequireConfig req, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, boolean compression, boolean bestEffort, SanshainMojoDelegate delegate) throws MojoExecutionException {
        String reqServiceName = req.getServiceName();
        String apiType = req.getApiType();
        String version = req.getVersion();

        File outputDirectory = resolveOutputDirectory(req);

        List<SanshainConfig.EndpointConfig> endpoints = req.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            getLog().warn("No endpoints defined for service: " + reqServiceName);
            return;
        }

        try {
            if (endpoints.size() >= 2) {
                requireBundle(req, client, cache, url, token, serviceName, version, compression, apiType, outputDirectory);
            } else {
                requireSingle(req, endpoints.get(0), client, cache, url, token, serviceName, version, compression, apiType, outputDirectory);
            }
        } catch (Exception e) {
            delegate.handleException(e, bestEffort);
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

    private void requireBundle(SanshainConfig.RequireConfig req, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String version, boolean compression, String apiType, File outputDirectory) throws IOException, MojoExecutionException {
        String reqServiceName = req.getServiceName();
        getLog().info("Requiring bundle: " + reqServiceName + "@" + version + " (" + req.getEndpoints().size() + " endpoints)");

        String cacheKey = SanshainCache.requireBundleKey(reqServiceName, version);
        SanshainCache.RequireEntry cachedEntry = cache.getRequireEntry(cacheKey);
        String cachedEtag = cachedEntry != null ? cachedEntry.etag : null;

        RequireResult result = client.postRequireBundleWithEtag(url, token, serviceName, reqServiceName, version, req.getEndpoints(), compression, dryRun, apiType, cachedEtag);

        if (result.isNotModified()) {
            getLog().info("⏭ " + reqServiceName + " spec unchanged (304), skipping code generation.");
        } else {
            String fileName = reqServiceName + "_bundle." + ("proto".equalsIgnoreCase(apiType) ? "proto" : "yaml");
            Path outputFile = outputDirectory.toPath().resolve(fileName);
            Files.writeString(outputFile, result.getContent());
            getLog().info("Saved bundle: " + outputFile + servedAs(result));

            if (result.getEtag() != null) {
                cache.updateRequireEntry(cacheKey, result.getEtag());
                cache.save();
            }
        }
    }

    private void requireSingle(SanshainConfig.RequireConfig req, SanshainConfig.EndpointConfig endpoint, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String version, boolean compression, String apiType, File outputDirectory) throws IOException, MojoExecutionException {
        String reqServiceName = req.getServiceName();
        String method = endpoint.getMethod();
        String path = endpoint.getPath();

        getLog().info("Requiring: " + reqServiceName + "@" + version + " " + method + " " + path);

        String cacheKey = SanshainCache.requireKey(reqServiceName, version, method, path);
        SanshainCache.RequireEntry cachedEntry = cache.getRequireEntry(cacheKey);
        String cachedEtag = cachedEntry != null ? cachedEntry.etag : null;

        RequireResult result = client.getRequireWithEtag(url, token, serviceName, reqServiceName, version, path, method, compression, dryRun, apiType, cachedEtag);

        if (result.isNotModified()) {
            getLog().info("⏭ " + reqServiceName + " spec unchanged (304), skipping code generation.");
        } else {
            String fileName = reqServiceName + "_" + path.replace("/", "_").replaceFirst("^_", "") + "_" + method + "." + ("proto".equalsIgnoreCase(apiType) ? "proto" : "yaml");
            Path outputFile = outputDirectory.toPath().resolve(fileName);
            Files.writeString(outputFile, result.getContent());
            getLog().info("Saved: " + outputFile + servedAs(result));

            if (result.getEtag() != null) {
                cache.updateRequireEntry(cacheKey, result.getEtag());
                cache.save();
            }
        }
    }

    private String servedAs(RequireResult result) {
        return result.getStability() != null ? " (served: " + result.getStability() + ")" : "";
    }
}
