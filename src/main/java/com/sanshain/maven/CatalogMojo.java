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

/**
 * Goal which pushes this system's Backstage {@code catalog-info.yaml} (the {@code catalogInfo}
 * path in sanshain.yaml) to {@code PUT /catalog/<serviceName>}, so the network graph can show the
 * peripheral resources it depends on. Needs Sanshain Service 2.4.0 or later.
 */
@Mojo(name = "catalog", defaultPhase = LifecyclePhase.INITIALIZE, requiresProject = false)
public class CatalogMojo extends AbstractMojo {

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

    /**
     * Fail on missing configuration and on unknown sanshain.yaml fields. Unset, the
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

    @Parameter(property = "sanshain.bestEffort")
    private Boolean bestEffort;

    /**
     * Declares this build as the trunk stream's: the periphery updates the main graph as well as
     * the dev graph. Also settable via {@code SANSHAIN_TRUNK=true}.
     */
    @Parameter(property = "sanshain.trunk")
    private Boolean trunk;

    /**
     * A sanshain-branch build. The catalog call takes no tag, so a tagged build pushes to the dev
     * graph only; declared here so trunk and tag together still fail as on the other goals.
     */
    @Parameter(property = "sanshain.tag")
    private String tag;

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

        if (skip) {
            getLog().info("Skipping sanshain:catalog (sanshain.skip=true)");
            return;
        }

        getLog().debug("Sanshain Maven Plugin v" + getClass().getPackage().getImplementationVersion());
        getLog().debug("Config file: " + configFile.getAbsolutePath() + " (exists: " + configFile.exists() + ")");

        boolean resolvedBestEffort = delegate.resolveBestEffort(bestEffort, null);

        SanshainConfig config;
        try {
            config = new SanshainConfig().loadConfig(configFile, delegate);
            resolvedBestEffort = delegate.resolveBestEffort(bestEffort, config);
        } catch (MojoFailureException e) {
            // Unknown fields under strict: a configuration error best-effort does not soften.
            throw e;
        } catch (Exception e) {
            delegate.handleException(e, resolvedBestEffort);
            config = new SanshainConfig();
        }

        String resolvedUrl = delegate.resolveUrl(sanshainUrl, config);
        String resolvedServiceName = delegate.resolveServiceName(serviceName, config);
        String resolvedToken = delegate.resolveToken(token);
        boolean resolvedCompression = delegate.resolveCompression(compression, config);
        boolean resolvedInsecure = delegate.resolveInsecure(insecure, config);
        SanshainStream stream = delegate.resolveStream(trunk, tag);

        if (resolvedServiceName == null) {
            delegate.abort("serviceName is required (either in pom.xml or sanshain.yaml)", resolvedBestEffort);
            return;
        }

        // Only the missing catalogInfo itself is strict's business here; best-effort covers
        // failed calls, not this configuration check.
        if (config.getCatalogInfo() == null) {
            if (delegate.isStrict()) {
                throw new MojoExecutionException("strict: sanshain.yaml has no catalogInfo");
            }
            getLog().warn("No catalogInfo in sanshain.yaml - skipping catalog.");
            return;
        }

        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }
        getLog().debug("Resolved sanshainUrl: " + resolvedUrl);
        getLog().debug("Resolved serviceName: " + resolvedServiceName);
        getLog().debug("Resolved compression: " + resolvedCompression);
        getLog().debug("Resolved insecure: " + resolvedInsecure);
        getLog().debug("Resolved token: " + (resolvedToken != null ? "[set]" : "[not set]"));
        getLog().debug("Best effort: " + resolvedBestEffort);
        getLog().debug("Resolved stream: " + stream);

        try {
            File catalogFile = config.resolvePath(config.getCatalogInfo());
            String content;
            try {
                content = Files.readString(catalogFile.toPath());
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot read catalogInfo file " + catalogFile.getAbsolutePath()
                        + ": " + e.getMessage(), e);
            }

            getLog().info("Pushing catalog for " + resolvedServiceName + ": " + catalogFile.getAbsolutePath());
            SanshainHttpClient client = new SanshainHttpClient(getLog(), resolvedInsecure);
            CatalogResponse response = client.putCatalog(resolvedUrl, resolvedToken, resolvedServiceName, content,
                    catalogFile.getAbsolutePath(), resolvedCompression, stream.isTrunk());

            getLog().info(response.toSummary());
            for (String resource : response.getResources()) {
                getLog().info("  " + resource);
            }
        } catch (Exception e) {
            delegate.handleException(e, resolvedBestEffort);
        }
    }
}
