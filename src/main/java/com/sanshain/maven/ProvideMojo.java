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

/**
 * Goal which provides an API specification to the Sanshain service under its declared stability.
 * The version of the Provide is read from the spec file itself ({@code info.version}, or a
 * {@code // sanshain-version:} comment for proto).
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

    @Parameter(property = "sanshain.insecure")
    private Boolean insecure;

    @Parameter(property = "sanshain.compression")
    private Boolean compression;

    @Parameter(property = "sanshain.combine")
    private Boolean combine;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    @Parameter(property = "sanshain.openapi.file")
    private File openApiFile;

    @Parameter(property = "sanshain.service.name")
    private String serviceName;

    @Parameter(property = "sanshain.provide.skip", defaultValue = "false")
    private boolean skipProvide;

    @Parameter(property = "sanshain.bestEffort")
    private Boolean bestEffort;

    /**
     * The explicit GA switch: when true, this build provides as {@code ga} instead of the default
     * {@code snapshot}. Also settable via {@code SANSHAIN_GA=true}.
     */
    @Parameter(property = "sanshain.ga", defaultValue = "false")
    private boolean ga;

    /**
     * Declares this build as the trunk stream's: its versions and pins maintain the main graph.
     * Set by trunk CI, not committed to sanshain.yaml. Also settable via {@code SANSHAIN_TRUNK=true}.
     */
    @Parameter(property = "sanshain.trunk")
    private Boolean trunk;

    /**
     * Declares this build as a named sanshain-branch's — a release or hotfix pipeline updating
     * that graph instead of trunk. Also settable via {@code SANSHAIN_TAG}. Mutually exclusive
     * with {@link #trunk}; the branch must already exist or the server answers 404.
     */
    @Parameter(property = "sanshain.tag")
    private String tag;

    /** Set in {@link #execute()}; read by the provide helpers, like {@code baseDir}. */
    private SanshainMojoDelegate delegate;

    /** Resolved once in {@link #execute()}; the stability declared on every Provide of this run. */
    private String stability;

    /** Resolved once in {@link #execute()}; the graph every call of this run speaks for. */
    private SanshainStream stream;

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
        delegate = new SanshainMojoDelegate(getLog(), settings, serverId, strict);

        if (openApiFile == null) {
            openApiFile = new File(new File(baseDir, "target"), "openapi.yaml");
        }

        if (skip || skipProvide) {
            getLog().info("Skipping sanshain:provide (" + (skipProvide ? "sanshain.provide.skip" : "sanshain.skip") + "=true)");
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
        String resolvedServiceName = delegate.resolveServiceName(serviceName, config);
        String resolvedToken = delegate.resolveToken(token);
        boolean resolvedCompression = delegate.resolveCompression(compression, config);
        boolean resolvedInsecure = delegate.resolveInsecure(insecure, config);
        boolean resolvedCombine = delegate.resolveCombine(combine, config);
        stability = delegate.resolveStability(ga);
        stream = delegate.resolveStream(trunk, tag);

        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        if (resolvedServiceName == null) {
            delegate.abort("serviceName is required (either in pom.xml or sanshain.yaml)", resolvedBestEffort);
            return;
        }

        logResolvedValues(resolvedUrl, resolvedServiceName, resolvedCompression, resolvedInsecure, resolvedToken, resolvedBestEffort, resolvedCombine);

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
        SanshainCache cache = new SanshainCache(new File(baseDir, "target"));

        if (dryRun) {
            getLog().info("Dry-run mode enabled — spec will be validated but not stored.");
        }

        try {
            processProvides(config, client, resolvedUrl, resolvedToken, resolvedServiceName, resolvedCompression, resolvedCombine, cache, resolvedBestEffort);
        } catch (Exception e) {
            delegate.handleException(e, resolvedBestEffort);
        }
    }

    private void logResolvedValues(String url, String name, boolean comp, boolean ins, String tok, boolean be, boolean comb) {
        getLog().debug("Resolved sanshainUrl: " + url);
        getLog().debug("Resolved serviceName: " + name);
        getLog().debug("Resolved compression: " + comp);
        getLog().debug("Resolved insecure: " + ins);
        getLog().debug("Resolved token: " + (tok != null ? "[set]" : "[not set]"));
        getLog().debug("Best effort: " + be);
        getLog().debug("Resolved combine: " + comb);
        getLog().debug("Resolved stability: " + stability);
        getLog().debug("Resolved stream: " + stream);
    }

    private void processProvides(SanshainConfig config, SanshainHttpClient client, String url, String token, String serviceName, boolean compression, boolean defaultCombine, SanshainCache cache, boolean bestEffort) throws IOException, MojoExecutionException {
        boolean providedAnything = false;

        // Handle 'provides' list
        if (config.getProvides() != null && !config.getProvides().isEmpty()) {
            for (SanshainConfig.ProvideConfig p : config.getProvides()) {
                if (p.isRetired()) {
                    retireFamily(client, url, token, serviceName, p.getApiType(), compression);
                    providedAnything = true;
                } else if (p.getFile() != null) {
                    boolean itemCombine = delegate.resolveCombine(p.getCombine(), defaultCombine, config);
                    provideFile(client, url, token, serviceName, new File(baseDir, p.getFile()), p.getApiType(), compression, itemCombine, dryRun, cache);
                    providedAnything = true;
                }
            }
        }

        // Handle single 'provide'
        if (config.getProvide() != null) {
            SanshainConfig.ProvideConfig p = config.getProvide();
            if (p.isRetired()) {
                retireFamily(client, url, token, serviceName, p.getApiType(), compression);
                providedAnything = true;
            } else if (p.getFile() != null || p.getOpenApiFile() != null || p.getAsyncApiFile() != null || p.getProtoFile() != null) {
                boolean itemCombine = delegate.resolveCombine(p.getCombine(), defaultCombine, config);
                provideSingleConfig(client, url, token, serviceName, p, compression, itemCombine, cache);
                providedAnything = true;
            }
        }

        // Fallback to default openApiFile
        if (!providedAnything && openApiFile != null && openApiFile.exists()) {
            provideFile(client, url, token, serviceName, openApiFile, "openapi", compression, defaultCombine, dryRun, cache);
            providedAnything = true;
        }

        if (!providedAnything) {
            delegate.abort("No specification files found to provide. Configure provide in sanshain.yaml or disable sanshain.strict.", bestEffort);
        }
    }

    private void provideSingleConfig(SanshainHttpClient client, String url, String token, String serviceName, SanshainConfig.ProvideConfig p, boolean compression, boolean combine, SanshainCache cache) throws IOException, MojoExecutionException {
        if (p.getFile() != null) {
            provideFile(client, url, token, serviceName, new File(baseDir, p.getFile()), p.getApiType(), compression, combine, dryRun, cache);
        }
        if (p.getOpenApiFile() != null) {
            provideFile(client, url, token, serviceName, new File(baseDir, p.getOpenApiFile()), "openapi", compression, combine, dryRun, cache);
        }
        if (p.getAsyncApiFile() != null) {
            provideFile(client, url, token, serviceName, new File(baseDir, p.getAsyncApiFile()), "asyncapi", compression, combine, dryRun, cache);
        }
        if (p.getProtoFile() != null) {
            provideFile(client, url, token, serviceName, new File(baseDir, p.getProtoFile()), "proto", compression, combine, dryRun, cache);
        }
    }

    private void provideFile(SanshainHttpClient client, String url, String token, String serviceName, File file, String apiType, boolean compression, boolean combine, boolean dryRun, SanshainCache cache) throws IOException, MojoExecutionException {
        if (!file.exists()) {
            throw new MojoExecutionException("Specification file does not exist: " + file.getAbsolutePath());
        }

        String content;
        if (combine) {
            getLog().info("Combining multi-file specification: " + file.getAbsolutePath());
            content = new SpecCombiner().combine(file, apiType);
        } else {
            content = Files.readString(file.toPath());
        }

        String fileKey = file.getName();

        // Client-side content caching — skip if unchanged
        String contentHash = SanshainCache.computeHash(content);
        if (cache != null) {
            SanshainCache.ProvideEntry cached = cache.getProvideEntry(fileKey);
            if (cached != null && contentHash.equals(cached.contentHash)) {
                getLog().info("⏭ Spec unchanged (hash match), skipping provide.");
                return;
            }
        }

        String type;
        if (apiType == null || apiType.equalsIgnoreCase("openapi")) {
            type = "openapi";
            getLog().info("Providing OpenAPI: " + serviceName + " (stability: " + stability + ")");
        } else if (apiType.equalsIgnoreCase("asyncapi")) {
            type = "asyncapi";
            getLog().info("Providing AsyncAPI: " + serviceName + " (stability: " + stability + ")");
        } else if (apiType.equalsIgnoreCase("proto") || apiType.equalsIgnoreCase("grpc")) {
            type = "proto";
            getLog().info("Providing Protocol Buffers: " + serviceName + " (stability: " + stability + ")");
        } else {
            throw new MojoExecutionException("Unsupported apiType: " + apiType);
        }

        ProvideResponse response = client.postProvide(url, token, serviceName, content, stability, compression, dryRun, type, file.getName(), stream);

        if (response != null) {
            reportHarvestedSubscriptions(response);
        }

        // Save content_hash from response so unchanged specs skip the next upload
        if (cache != null && response != null) {
            String responseHash = response.getContentHash() != null ? response.getContentHash() : contentHash;
            cache.updateProvideEntry(fileKey, responseHash);
            cache.save();
        }
    }

    /**
     * Surfaces the {@code subscribe} operations Sanshain harvested from an AsyncAPI
     * document. Without this the feature is invisible: the server accepts the
     * provide and records the consumer edges either way, so a subscription that
     * expects a field no contract guarantees would only ever be discovered later,
     * on the GA provide that refuses it.
     *
     * <p>Advisories never fail the build. The server already answers 409 for a GA
     * provide whose expectation is unsatisfiable; failing here as well would
     * punish the snapshot build that is telling you about it in time to fix it.
     */
    private void reportHarvestedSubscriptions(ProvideResponse response) {
        if (response.getHarvestedSubscriptions().isEmpty()) {
            return;
        }
        for (ProvideResponse.HarvestedSubscription sub : response.getHarvestedSubscriptions()) {
            if (sub.isAdvisory()) {
                getLog().warn("Subscription: " + sub.describe());
            } else {
                getLog().info("Subscription: " + sub.describe());
            }
        }
    }

    /**
     * Declares that this project no longer provides an API family, because its
     * {@code sanshain.yaml} entry says {@code retired: true}.
     */
    private void retireFamily(SanshainHttpClient client, String url, String token, String serviceName,
                              String apiType, boolean compression) throws MojoExecutionException {
        String type = apiType == null ? "openapi" : apiType.toLowerCase(java.util.Locale.ROOT);
        if (!type.equals("openapi") && !type.equals("asyncapi") && !type.equals("proto") && !type.equals("grpc")) {
            throw new MojoExecutionException("Unsupported apiType: " + apiType);
        }

        getLog().info("Retiring " + type + " for " + serviceName
                + (dryRun ? " (dry run — checking permission only)" : ""));
        RetiredProtocol result = client.postRetire(url, token, serviceName, compression, dryRun, type);
        if (dryRun) {
            getLog().info("✓ Retire would be permitted. Nothing was retired.");
        } else if (result != null) {
            getLog().info(result.toSummary());
        } else {
            getLog().info("✓ Family retired.");
        }
    }
}
