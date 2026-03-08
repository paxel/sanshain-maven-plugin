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

    @Parameter(defaultValue = "${project.build.directory}/sanshain-snippets", property = "outputDirectory")
    private File outputDirectory;

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
                if (clientName != null) {
                    requireConfig = config.getRequire().stream()
                            .filter(r -> clientName.equals(r.getClientName()))
                            .findFirst().orElse(null);
                }
                if (requireConfig == null && config.getRequire().size() == 1) {
                    requireConfig = config.getRequire().get(0);
                }

                if (requireConfig != null) {
                    if (clientName == null) clientName = requireConfig.getClientName();
                    if (requirements == null) requirements = requireConfig.getRequirements();
                    if (outputDirectory == null || outputDirectory.getPath().endsWith("target/sanshain-snippets")) {
                        if (requireConfig.getOutputDirectory() != null) {
                            outputDirectory = new File(requireConfig.getOutputDirectory());
                        }
                    }
                }
            }
        }

        if (clientName == null) {
             throw new MojoExecutionException("clientName is required (either in pom.xml or sanshain.yaml)");
        }
        if (requirements == null || requirements.isEmpty()) {
             throw new MojoExecutionException("requirements are required (either in pom.xml or sanshain.yaml)");
        }

        getLog().info("Requiring OpenAPI snippets for client: " + clientName);
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }
        // Implementation for downloading from SanShain service will go here
        for (EndpointRequirement req : requirements) {
            getLog().info("Requirement: " + req.getServiceName() + " " + req.getBranch() + " " + req.getPath() + " " + req.getMethod());
        }
    }

    public static class EndpointRequirement {
        private String serviceName;
        private String branch;
        private String path;
        private String method;

        // Getters and Setters needed for Maven parameter injection
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
    }
}
