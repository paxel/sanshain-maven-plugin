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

    @Parameter(property = "sanshain.openapi.file")
    private File openApiFile;

    @Parameter(property = "sanshain.service.name")
    private String serviceName;

    @Parameter(property = "sanshain.timeout")
    private Integer timeout;

    @Parameter(property = "sanshain.provide.skip", defaultValue = "false")
    private boolean skipProvide;

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
        
        String resolvedUrl = delegate.resolveUrl(sanshainUrl, config);
        String resolvedServiceName = delegate.resolveServiceName(serviceName, config);
        String resolvedToken = delegate.resolveToken(token);
        String resolvedBranch = delegate.resolveBranch(branch);
        boolean resolvedCompression = delegate.resolveCompression(compression, config);
        boolean resolvedInsecure = delegate.resolveInsecure(insecure, config);
        boolean bestEffort = config.getBestEffort() != null && config.getBestEffort();

        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        if (resolvedServiceName == null) {
            delegate.abort("serviceName is required (either in pom.xml or sanshain.yaml)");
            return;
        }

        logResolvedValues(resolvedUrl, resolvedServiceName, resolvedCompression, resolvedInsecure, resolvedBranch, resolvedToken, bestEffort);

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
        SanshainCache cache = new SanshainCache(new File(baseDir, "target"));
        
        if (dryRun) {
            getLog().info("Dry-run mode enabled — spec will be validated but not stored.");
        }

        try {
            processProvides(config, client, resolvedUrl, resolvedToken, resolvedServiceName, resolvedBranch, resolvedCompression, cache, delegate);
        } catch (MojoExecutionException e) {
            handleException(e, bestEffort);
        } catch (IOException e) {
            handleException(new MojoExecutionException("Failed to read specification file", e), bestEffort);
        } catch (Exception e) {
            handleException(new MojoExecutionException("Failed to provide specification", e), bestEffort);
        }
    }

    private void logResolvedValues(String url, String name, boolean comp, boolean ins, String br, String tok, boolean be) {
        getLog().debug("Resolved sanshainUrl: " + url);
        getLog().debug("Resolved serviceName: " + name);
        getLog().debug("Resolved compression: " + comp);
        getLog().debug("Resolved insecure: " + ins);
        getLog().debug("Resolved branch: " + br);
        getLog().debug("Resolved token: " + (tok != null ? "[set]" : "[not set]"));
        getLog().debug("Best effort: " + be);
    }

    private void processProvides(SanshainConfig config, SanshainHttpClient client, String url, String token, String serviceName, String defaultBranch, boolean compression, SanshainCache cache, SanshainMojoDelegate delegate) throws IOException, MojoExecutionException {
        boolean providedAnything = false;

        // Handle 'provides' list
        if (config.getProvides() != null && !config.getProvides().isEmpty()) {
            for (SanshainConfig.ProvideConfig p : config.getProvides()) {
                if (p.getFile() != null) {
                    provideConfiguredFile(client, url, token, serviceName, defaultBranch, p, compression, cache);
                    providedAnything = true;
                }
            }
        }

        // Handle single 'provide'
        if (config.getProvide() != null) {
            SanshainConfig.ProvideConfig p = config.getProvide();
            if (p.getFile() != null || p.getOpenApiFile() != null || p.getAsyncApiFile() != null || p.getProtoFile() != null) {
                provideSingleConfig(client, url, token, serviceName, defaultBranch, p, compression, cache);
                providedAnything = true;
            }
        }

        // Fallback to default openApiFile
        if (!providedAnything && openApiFile != null && openApiFile.exists()) {
            provideFile(client, url, token, serviceName, defaultBranch, openApiFile, "openapi", compression, dryRun, null, cache);
            providedAnything = true;
        }

        if (!providedAnything) {
            delegate.abort("No specification files found to provide. Configure provide in sanshain.yaml or disable sanshain.strict.");
        }
    }

    private void provideConfiguredFile(SanshainHttpClient client, String url, String token, String serviceName, String defaultBranch, SanshainConfig.ProvideConfig p, boolean compression, SanshainCache cache) throws IOException, MojoExecutionException {
        String b = p.getBranch() != null ? p.getBranch() : defaultBranch;
        provideFile(client, url, token, serviceName, b, new File(baseDir, p.getFile()), p.getApiType(), compression, dryRun, p.getBaseVersion(), cache);
    }

    private void provideSingleConfig(SanshainHttpClient client, String url, String token, String serviceName, String defaultBranch, SanshainConfig.ProvideConfig p, boolean compression, SanshainCache cache) throws IOException, MojoExecutionException {
        String b = p.getBranch() != null ? p.getBranch() : defaultBranch;
        
        if (p.getFile() != null) {
            provideFile(client, url, token, serviceName, b, new File(baseDir, p.getFile()), p.getApiType(), compression, dryRun, p.getBaseVersion(), cache);
        }
        if (p.getOpenApiFile() != null) {
            provideFile(client, url, token, serviceName, b, new File(baseDir, p.getOpenApiFile()), "openapi", compression, dryRun, p.getBaseVersion(), cache);
        }
        if (p.getAsyncApiFile() != null) {
            provideFile(client, url, token, serviceName, b, new File(baseDir, p.getAsyncApiFile()), "asyncapi", compression, dryRun, p.getBaseVersion(), cache);
        }
        if (p.getProtoFile() != null) {
            provideFile(client, url, token, serviceName, b, new File(baseDir, p.getProtoFile()), "proto", compression, dryRun, p.getBaseVersion(), cache);
        }
    }

    private void handleException(Exception e, boolean bestEffort) throws MojoExecutionException {
        if (bestEffort) {
            getLog().warn("Sanshain provide failed (best effort): " + e.getMessage());
        } else if (e instanceof MojoExecutionException) {
            throw (MojoExecutionException) e;
        } else {
            throw new MojoExecutionException(e.getMessage(), e);
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
                getLog().info("⏭ Spec unchanged (hash match), skipping provide.");
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
}
