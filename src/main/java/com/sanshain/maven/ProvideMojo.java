package com.sanshain.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Goal which provides an OpenAPI specification to the SanShain service.
 */
@Mojo(name = "provide", defaultPhase = LifecyclePhase.PACKAGE)
public class ProvideMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/sanshain.yaml", property = "configFile")
    private File configFile;

    @Parameter(defaultValue = "${project.build.directory}/openapi.yaml", property = "openApiFile")
    private File openApiFile;

    @Parameter(property = "serviceName")
    private String serviceName;

    @Parameter(property = "branch", defaultValue = "main")
    private String branch;

    @Parameter(property = "sanshainUrl", defaultValue = "http://localhost:8080")
    private String sanshainUrl;

    public void execute() throws MojoExecutionException {
        SanshainConfig config = ConfigLoader.loadConfig(configFile);
        if (config != null) {
            if (config.getSanshainUrl() != null && (sanshainUrl == null || "http://localhost:8080".equals(sanshainUrl))) {
                sanshainUrl = config.getSanshainUrl();
            }
            if (config.getProvide() != null) {
                SanshainConfig.ProvideConfig provideConfig = config.getProvide();
                if (serviceName == null) serviceName = provideConfig.getServiceName();
                if (branch == null || "main".equals(branch)) {
                    if (provideConfig.getBranch() != null) branch = provideConfig.getBranch();
                }
                if (openApiFile == null || openApiFile.getPath().endsWith("target/openapi.yaml")) {
                     if (provideConfig.getOpenApiFile() != null) openApiFile = new File(provideConfig.getOpenApiFile());
                }
            }
        }

        if (serviceName == null) {
            throw new MojoExecutionException("serviceName is required (either in pom.xml or sanshain.yaml)");
        }

        getLog().info("Providing OpenAPI spec for service: " + serviceName + " (branch: " + branch + ")");
        if (!openApiFile.exists()) {
            throw new MojoExecutionException("OpenAPI file does not exist: " + openApiFile.getAbsolutePath());
        }
        // Implementation for uploading to SanShain service will go here
        getLog().info("OpenAPI file found: " + openApiFile.getAbsolutePath());
    }
}
