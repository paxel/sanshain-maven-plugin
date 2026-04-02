package com.sanshain.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Goal which provides an OpenAPI specification to the Sanshain service.
 */
@Mojo(name = "provide", defaultPhase = LifecyclePhase.INITIALIZE)
public class ProvideMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.basedir}/sanshain.yaml", property = "configFile")
    private File configFile;

    @Parameter(defaultValue = "${project.build.directory}/openapi.yaml", property = "openApiFile")
    private File openApiFile;

    @Parameter(property = "serviceName")
    private String serviceName;

    @Parameter(property = "sanshain.url")
    private String sanshainUrl;

    @Parameter(property = "sanshain.token")
    private String token;

    @Parameter(property = "sanshain.timeout")
    private Integer timeout;

    @Parameter(property = "sanshain.compression")
    private Boolean compression;

    @Parameter(property = "sanshain.serverId", defaultValue = "sanshain")
    private String serverId;

    @Parameter(property = "sanshain.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "sanshain.provide.skip", defaultValue = "false")
    private boolean skipProvide;

    @Parameter(property = "sanshain.dry.run", defaultValue = "false")
    private boolean dryRun;

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    public void execute() throws MojoExecutionException {
        if (skip || skipProvide) {
            getLog().info("Skipping sanshain:provide (" + (skipProvide ? "sanshain.provide.skip" : "sanshain.skip") + "=true)");
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

        // Resolve provide config
        if (config.getProvide() != null) {
            SanshainConfig.ProvideConfig provideConfig = config.getProvide();
            if (serviceName == null) serviceName = provideConfig.getServiceName();
            if (openApiFile.getPath().endsWith("target/openapi.yaml") && provideConfig.getOpenApiFile() != null) {
                openApiFile = new File(baseDir, provideConfig.getOpenApiFile());
            }
        }

        // Resolve token: env > settings.xml > maven property
        String resolvedToken = resolveToken();
        if (resolvedToken == null) {
            getLog().warn("No authentication token configured. Requests will be unauthenticated.");
        }

        // Resolve compression
        boolean resolvedCompression = resolveCompression(config);

        // Resolve branch
        String branch = System.getenv("SANSHAIN_BRANCH");
        if (branch == null) {
            branch = getGitBranch();
        }
        if (branch == null) {
            branch = "main";
        }

        if (serviceName == null) {
            throw new MojoExecutionException("serviceName is required (either in pom.xml or sanshain.yaml)");
        }

        getLog().debug("Resolved sanshainUrl: " + sanshainUrl);
        getLog().debug("Resolved serviceName: " + serviceName);
        getLog().debug("Resolved compression: " + resolvedCompression);
        getLog().debug("Resolved branch: " + branch);
        getLog().debug("Resolved token: " + (resolvedToken != null ? "[set]" : "[not set]"));
        getLog().debug("OpenAPI file: " + openApiFile.getAbsolutePath());

        getLog().info("Providing OpenAPI spec for service: " + serviceName + " (branch: " + branch + ")");
        if (!openApiFile.exists()) {
            throw new MojoExecutionException("OpenAPI file does not exist: " + openApiFile.getAbsolutePath());
        }

        String openapiYaml;
        try {
            openapiYaml = Files.readString(openApiFile.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read OpenAPI file: " + openApiFile.getAbsolutePath(), e);
        }

        SanshainHttpClient client = new SanshainHttpClient(getLog());
        if (dryRun) {
            getLog().info("Dry-run mode enabled — spec will be validated but not stored.");
        }

        client.postProvide(sanshainUrl, resolvedToken, serviceName, branch, openapiYaml, resolvedCompression, dryRun);
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

    private String getGitBranch() {
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            try (Repository repository = builder.readEnvironment()
                    .findGitDir(baseDir)
                    .build()) {
                if (repository != null) {
                    return repository.getBranch();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            getLog().debug("Could not determine git branch: " + e.getMessage());
        }
        return null;
    }
}
