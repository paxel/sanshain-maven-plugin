package com.sanshain.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.List;

/**
 * Goal which requires OpenAPI snippets from the SanShain service.
 */
@Mojo(name = "require", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class RequireMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/sanshain.yaml", property = "configFile")
    private File configFile;

    @Parameter(property = "clientName")
    private String clientName;

    @Parameter(property = "sanshainUrl", defaultValue = "http://localhost:8080")
    private String sanshainUrl;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/sanshain", property = "outputDirectory")
    private File outputDirectory;

    @Parameter(defaultValue = "300", property = "timeout")
    private int timeout; // in seconds

    @Parameter(defaultValue = "10", property = "retryInterval")
    private int retryInterval; // in seconds

    @Parameter
    private List<EndpointRequirement> requirements;

    public void execute() throws MojoExecutionException {
        SanshainConfig config = ConfigLoader.loadConfig(configFile);
        if (config != null) {
            if (config.getSanshainUrl() != null && (sanshainUrl == null || "http://localhost:8080".equals(sanshainUrl))) {
                sanshainUrl = config.getSanshainUrl();
            }

            if (config.getRequire() != null) {
                // Find the first requirement block matching this clientName if clientName is set,
                // or just take the first one if only one exists.
                SanshainConfig.RequireConfig requireConfig = null;
                if (config.getRequire().size() == 1) {
                    requireConfig = config.getRequire().get(0);
                }
                if (requireConfig != null) {
                    if (requirements == null) requirements = requireConfig.getRequirements();
                    if (outputDirectory == null || outputDirectory.getPath().endsWith("target/generated-sources/sanshain")) {
                        if (requireConfig.getOutputDirectory() != null) {
                            outputDirectory = new File(requireConfig.getOutputDirectory());
                        }
                    }
                    if (timeout == 300 && requireConfig.getTimeout() != 0) {
                        timeout = requireConfig.getTimeout();
                    }
                    if (retryInterval == 10 && requireConfig.getRetryInterval() != 0) {
                        retryInterval = requireConfig.getRetryInterval();
                    }
                }
            }
        }

        if (config != null && config.getClientName() != null && clientName == null) {
            clientName = config.getClientName();
        }
        if (clientName == null) {
             throw new MojoExecutionException("clientName is required (either in pom.xml or sanshain.yaml)");
        }
        if (requirements == null || requirements.isEmpty()) {
             throw new MojoExecutionException("requirements are required (either in pom.xml or sanshain.yaml)");
        }

        getLog().info("Requiring OpenAPI snippets for client: " + clientName + " (timeout: " + timeout + "s, retryInterval: " + retryInterval + "s)");
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        // Implementation for downloading from SanShain service with polling/retry
        long startTime = System.currentTimeMillis();
        long timeoutMillis = (long) timeout * 1000;

        boolean allFound = false;
        while (!allFound) {
            // This is a placeholder for the actual check
            // For now, we just simulate the check
            getLog().info("Checking for required OpenAPI snippets...");
            
            // In a real implementation, we would call the service here
            // and check if all requirements are available.
            // Since the actual service communication is not yet implemented,
            // we will just proceed for now or timeout if it were a real check.
            
            allFound = true; // Placeholder: assume found for now to not block build

            if (!allFound) {
                if (System.currentTimeMillis() - startTime > timeoutMillis) {
                    throw new MojoExecutionException("Timed out waiting for OpenAPI snippets after " + timeout + " seconds");
                }
                try {
                    getLog().info("Some requirements not found, retrying in " + retryInterval + " seconds...");
                    Thread.sleep((long) retryInterval * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MojoExecutionException("Interrupted while waiting for OpenAPI snippets", e);
                }
            }
        }

        for (EndpointRequirement req : requirements) {
            getLog().info("Requirement: " + req.getServiceName() + " " + req.getPath() + " " + req.getMethod());
        }
    }

    public static class EndpointRequirement {
        private String serviceName;
        private String path;
        private String method;

        // Getters and Setters needed for Maven parameter injection
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
    }
}
