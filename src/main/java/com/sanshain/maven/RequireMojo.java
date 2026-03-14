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
import java.nio.file.Path;
import java.util.List;

/**
 * Goal which requires OpenAPI snippets from the SanShain service.
 */
@Mojo(name = "require", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class RequireMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.basedir}/sanshain.yaml", property = "configFile")
    private File configFile;

    @Parameter(property = "clientName")
    private String clientName;

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

    @Parameter(defaultValue = "${settings}", readonly = true)
    private Settings settings;

    public void execute() throws MojoExecutionException {
        SanshainConfig config = ConfigLoader.loadConfig(configFile);

        // Resolve sanshainUrl
        if (sanshainUrl == null && config.getSanshainUrl() != null) {
            sanshainUrl = config.getSanshainUrl();
        }
        if (sanshainUrl == null) {
            sanshainUrl = "http://localhost:8080";
        }

        // Resolve clientName
        if (clientName == null && config.getClientName() != null) {
            clientName = config.getClientName();
        }
        if (clientName == null) {
            throw new MojoExecutionException("clientName is required (either in pom.xml or sanshain.yaml)");
        }

        // Resolve token
        String resolvedToken = resolveToken();

        // Resolve global timeout (default 120)
        int globalTimeout = resolveGlobalTimeout(config);

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

        // Get requires from config
        List<SanshainConfig.RequireConfig> requires = config.getRequires();
        if (requires == null || requires.isEmpty()) {
            throw new MojoExecutionException("requires are required in sanshain.yaml");
        }

        SanshainHttpClient client = new SanshainHttpClient(getLog());

        for (SanshainConfig.RequireConfig req : requires) {
            String reqServiceName = req.getServiceName();
            int reqTimeout = req.getTimeout() != null ? req.getTimeout() : globalTimeout;

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

            for (SanshainConfig.EndpointConfig endpoint : endpoints) {
                String method = endpoint.getMethod();
                String path = endpoint.getPath();

                getLog().info("Requiring: " + reqServiceName + " " + method + " " + path +
                        " (branch: " + branch + ", timeout: " + reqTimeout + "s)");

                String yamlContent = client.getRequire(sanshainUrl, resolvedToken, clientName,
                        reqServiceName, branch, path, method, reqTimeout, resolvedCompression);

                // Save to file: {outputDirectory}/{serviceName}_{path}_{method}.yaml
                String fileName = reqServiceName + "_" +
                        path.replace("/", "_").replaceFirst("^_", "") +
                        "_" + method + ".yaml";
                Path outputFile = outputDirectory.toPath().resolve(fileName);
                try {
                    Files.writeString(outputFile, yamlContent);
                    getLog().info("Saved: " + outputFile);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to write file: " + outputFile, e);
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
        } catch (IOException e) {
            getLog().debug("Could not determine git branch: " + e.getMessage());
        }
        return null;
    }
}
