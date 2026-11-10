package com.sanshain.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

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

    /**
     * Fail on missing configuration, on unknown sanshain.yaml fields, on a build declaring both
     * trunk and a tag, and on any warning Sanshain sends with a response. Unset, the
     * {@code strict} field of sanshain.yaml decides; set (true or false), it wins.
     */
    @Parameter(property = "sanshain.strict")
    private Boolean strict;

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

    /**
     * Declares this build as the trunk stream's: its pins are recorded in the trunk store that
     * feeds the main graph. Set by trunk CI, not committed to sanshain.yaml. Also settable via
     * {@code SANSHAIN_TRUNK=true}.
     */
    @Parameter(property = "sanshain.trunk")
    private Boolean trunk;

    /**
     * Declares this build as a named sanshain-branch's — a release or hotfix pipeline whose pins
     * update that graph instead of trunk. Also settable via {@code SANSHAIN_TAG}. Mutually
     * exclusive with {@link #trunk} (both: recorded in neither, with a warning). An unknown branch
     * is not an error: Sanshain warns and records the build in no release graph.
     */
    @Parameter(property = "sanshain.tag")
    private String tag;

    /**
     * The Maven user properties ({@code -DNAME=value}): the values of the {@code {{NAME}}}
     * placeholders in the annotations of sanshain.yaml, before {@code SANSHAIN_SET_NAME}.
     */
    @Parameter(defaultValue = "${session.userProperties}", readonly = true)
    private Properties userProperties;

    /** Resolved once in {@link #execute()}; the graph every pin of this run belongs to. */
    private SanshainStream stream;

    /** Resolved once in {@link #execute()}; every require of this run sends the system ones. */
    private SanshainAnnotations annotations;

    private void initDefaults() {
        if (baseDir == null) {
            baseDir = new File(".");
        }
        if (configFile == null) {
            configFile = new File(baseDir, "sanshain.yaml");
        }
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        initDefaults();
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(getLog(), settings, serverId, strict);

        if (skip || skipRequire) {
            getLog().info("Skipping sanshain:require (" + (skipRequire ? "sanshain.require.skip" : "sanshain.skip") + "=true)");
            return;
        }

        getLog().debug("Sanshain Maven Plugin v" + getClass().getPackage().getImplementationVersion());
        getLog().debug("Config file: " + configFile.getAbsolutePath() + " (exists: " + configFile.exists() + ")");

        // A broken sanshain.yaml fails; best effort from the property or the variable skips.
        Optional<SanshainConfig> loaded = delegate.loadConfig(configFile, bestEffort);
        if (loaded.isEmpty()) {
            return;
        }
        SanshainConfig config = loaded.get();
        boolean resolvedBestEffort = delegate.resolveBestEffort(bestEffort, config);

        String resolvedUrl = delegate.resolveUrl(sanshainUrl, config);
        String resolvedServiceName = resolveServiceName(config);

        if (resolvedServiceName == null) {
            delegate.abort("serviceName is required (either in pom.xml or sanshain.yaml)", resolvedBestEffort, config);
            return;
        }

        String resolvedToken = delegate.resolveToken(token);
        boolean resolvedCompression = delegate.resolveCompression(compression, config);
        boolean resolvedInsecure = delegate.resolveInsecure(insecure, config);
        stream = delegate.resolveStream(trunk, tag);
        annotations = delegate.resolveAnnotations(config, userProperties);

        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        logResolvedValues(resolvedUrl, resolvedServiceName, resolvedCompression, resolvedInsecure, resolvedToken);

        List<SanshainConfig.RequireConfig> requires = config.getRequires();
        if (requires == null || requires.isEmpty()) {
            delegate.abort("No requires configured in sanshain.yaml.", resolvedBestEffort, config);
            return;
        }

        SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
        SanshainCache cache = new SanshainCache(new File(baseDir, "target"));

        if (dryRun) {
            getLog().info("Dry-run mode enabled — endpoints will be validated but no dependencies recorded.");
        }

        for (SanshainConfig.RequireConfig req : requires) {
            processRequire(req, config, client, cache, resolvedUrl, resolvedToken, resolvedServiceName, resolvedCompression, resolvedBestEffort, delegate);
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
        getLog().debug("Resolved stream: " + stream);
        getLog().debug("Resolved " + annotations);
    }

    private void processRequire(SanshainConfig.RequireConfig req, SanshainConfig config, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, boolean compression, boolean bestEffort, SanshainMojoDelegate delegate) throws MojoExecutionException, MojoFailureException {
        String reqServiceName = req.getServiceName();
        String apiType = canonicalApiType(req.getApiType());
        String version = req.getVersion();

        File outputDirectory = resolveOutputDirectory(req, config);

        List<SanshainConfig.EndpointConfig> endpoints = req.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            getLog().warn("No endpoints defined for service: " + reqServiceName);
            return;
        }

        try {
            if (endpoints.size() >= 2) {
                requireBundle(req, client, cache, url, token, serviceName, version, compression, apiType, outputDirectory, delegate);
            } else {
                requireSingle(req, endpoints.get(0), client, cache, url, token, serviceName, version, compression, apiType, outputDirectory, delegate);
            }
        } catch (Exception e) {
            delegate.handleException(e, bestEffort);
        }
    }

    /**
     * The require's apiType as the service's contract spells it: lower case, and {@code grpc},
     * which a provide accepts too, as {@code proto}. The wire and the output file's extension
     * only know {@code proto}.
     */
    private static String canonicalApiType(String apiType) {
        if (apiType == null) {
            return null;
        }
        String type = apiType.toLowerCase(Locale.ROOT);
        return type.equals("grpc") ? "proto" : type;
    }

    private File resolveOutputDirectory(SanshainConfig.RequireConfig req, SanshainConfig config) {
        String outputDir = req.getOutputDirectory();
        // A yaml path resolves next to sanshain.yaml; the default belongs to the build's target.
        File dir = outputDir != null
                ? config.resolvePath(outputDir)
                : new File(baseDir, "target/generated-sources/sanshain");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void requireBundle(SanshainConfig.RequireConfig req, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String version, boolean compression, String apiType, File outputDirectory, SanshainMojoDelegate delegate) throws IOException, MojoExecutionException, MojoFailureException {
        String reqServiceName = req.getServiceName();
        getLog().info("Requiring bundle: " + reqServiceName + "@" + version + " (" + req.getEndpoints().size() + " endpoints)");

        String fileName = reqServiceName + "_bundle." + ("proto".equalsIgnoreCase(apiType) ? "proto" : "yaml");
        Path outputFile = outputDirectory.toPath().resolve(fileName);
        String cacheKey = SanshainCache.requireBundleKey(reqServiceName, version, req.getFormat(), outputKey(outputDirectory));
        String cachedEtag = cachedEtag(cache, cacheKey, outputFile);

        RequireResult result = delegate.checked(client, () -> client.postRequireBundleWithEtag(url, token, serviceName,
                reqServiceName, version, req.getEndpoints(), compression, dryRun, apiType, cachedEtag, stream, req.getFormat(),
                annotations));

        if (result.isNotModified()) {
            getLog().info("⏭ " + reqServiceName + " spec unchanged (304), skipping code generation.");
        } else {
            Files.writeString(outputFile, result.getContent());
            getLog().info("Saved bundle: " + outputFile + servedAs(result));

            // A dry run records nothing, locally either: its ETag must not short-circuit a real run.
            if (result.getEtag() != null && !dryRun) {
                cache.updateRequireEntry(cacheKey, result.getEtag());
                cache.save();
            }
        }
    }

    private void requireSingle(SanshainConfig.RequireConfig req, SanshainConfig.EndpointConfig endpoint, SanshainHttpClient client, SanshainCache cache, String url, String token, String serviceName, String version, boolean compression, String apiType, File outputDirectory, SanshainMojoDelegate delegate) throws IOException, MojoExecutionException, MojoFailureException {
        String reqServiceName = req.getServiceName();
        String method = endpoint.getMethod();
        String path = endpoint.getPath();

        getLog().info("Requiring: " + reqServiceName + "@" + version + " " + method + " " + path);

        String fileName = reqServiceName + "_" + path.replace("/", "_").replaceFirst("^_", "") + "_" + method + "." + ("proto".equalsIgnoreCase(apiType) ? "proto" : "yaml");
        Path outputFile = outputDirectory.toPath().resolve(fileName);
        String cacheKey = SanshainCache.requireKey(reqServiceName, version, method, path, req.getFormat(), outputKey(outputDirectory));
        String cachedEtag = cachedEtag(cache, cacheKey, outputFile);

        RequireResult result = delegate.checked(client, () -> client.getRequireWithEtag(url, token, serviceName,
                reqServiceName, version, path, method, compression, dryRun, apiType, cachedEtag, stream, req.getFormat(),
                annotations));

        if (result.isNotModified()) {
            getLog().info("⏭ " + reqServiceName + " spec unchanged (304), skipping code generation.");
        } else {
            Files.writeString(outputFile, result.getContent());
            getLog().info("Saved: " + outputFile + servedAs(result));

            // A dry run records nothing, locally either: its ETag must not short-circuit a real run.
            if (result.getEtag() != null && !dryRun) {
                cache.updateRequireEntry(cacheKey, result.getEtag());
                cache.save();
            }
        }
    }

    /**
     * The output directory as the cache key names it: relative to the project directory, with
     * forward slashes, so two entries writing to different directories never share an ETag.
     */
    private String outputKey(File outputDirectory) {
        Path project = baseDir.toPath().toAbsolutePath().normalize();
        Path output = outputDirectory.toPath().toAbsolutePath().normalize();
        try {
            return project.relativize(output).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            // Another root (a different drive): there is no relative path.
            return output.toString().replace(File.separatorChar, '/');
        }
    }

    /**
     * The ETag to send as If-None-Match: none when the output file is gone, so the answer is a
     * 200 that writes it again instead of a 304 that would leave the directory empty.
     */
    private static String cachedEtag(SanshainCache cache, String cacheKey, Path outputFile) {
        SanshainCache.RequireEntry cachedEntry = cache.getRequireEntry(cacheKey);
        if (cachedEntry == null || !Files.exists(outputFile)) {
            return null;
        }
        return cachedEntry.etag;
    }

    private String servedAs(RequireResult result) {
        return result.getStability() != null ? " (served: " + result.getStability() + ")" : "";
    }
}
