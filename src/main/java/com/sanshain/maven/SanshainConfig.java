package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Root configuration object for the Sanshain plugin.
 * Maps directly to the sanshain.yaml structure.
 */
public class SanshainConfig {
    private String sanshainUrl;
    private String clientName;
    private Integer timeout;
    private Boolean compression;
    private Boolean insecure;
    private ProvideConfig provide;
    @JsonProperty("requires")
    private List<RequireConfig> requires;

    /**
     * Loads the Sanshain configuration from the specified file and applies environment variable overrides.
     *
     * @param configFile the YAML configuration file
     * @return the loaded and resolved {@link SanshainConfig}
     * @throws MojoExecutionException if loading or parsing fails
     */
    public SanshainConfig loadConfig(File configFile) throws MojoExecutionException {
        SanshainConfig config;
        if (configFile == null || !configFile.exists()) {
            config = this;
        } else {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            try {
                config = mapper.readValue(configFile, SanshainConfig.class);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to load configuration from " + configFile.getAbsolutePath(), e);
            }
        }
        applyEnvOverrides(config);
        return config;
    }

    private void applyEnvOverrides(SanshainConfig config) {
        String url = System.getenv("SANSHAIN_URL");
        if (url != null) {
            config.setSanshainUrl(url);
        }
        String clientName = System.getenv("SANSHAIN_CLIENT_NAME");
        if (clientName != null) {
            config.setClientName(clientName);
        }
        String timeout = System.getenv("SANSHAIN_TIMEOUT");
        if (timeout != null) {
            config.setTimeout(Integer.parseInt(timeout));
        }
        String compression = System.getenv("SANSHAIN_COMPRESSION");
        if (compression != null) {
            config.setCompression(Boolean.parseBoolean(compression));
        }
        String insecure = System.getenv("SANSHAIN_INSECURE");
        if (insecure != null) {
            config.setInsecure(Boolean.parseBoolean(insecure));
        }
    }

    /**
     * Gets the URL of the Sanshain service.
     * @return the URL of the Sanshain service
     */
    public String getSanshainUrl() { return sanshainUrl; }

    /**
     * Sets the URL of the Sanshain service.
     * @param sanshainUrl the URL of the Sanshain service
     */
    public void setSanshainUrl(String sanshainUrl) { this.sanshainUrl = sanshainUrl; }

    /**
     * Gets the name of the client.
     * @return the name of the client
     */
    public String getClientName() { return clientName; }

    /**
     * Sets the name of the client.
     * @param clientName the name of the client
     */
    public void setClientName(String clientName) { this.clientName = clientName; }

    /**
     * Gets the timeout in seconds.
     * @return the timeout in seconds
     */
    public Integer getTimeout() { return timeout; }

    /**
     * Sets the timeout in seconds.
     * @param timeout the timeout in seconds
     */
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    /**
     * Gets whether compression is enabled.
     * @return true if compression is enabled
     */
    public Boolean getCompression() { return compression; }

    /**
     * Sets whether compression should be enabled.
     * @param compression true if compression should be enabled
     */
    public void setCompression(Boolean compression) { this.compression = compression; }

    /**
     * Gets whether to ignore SSL certificate errors.
     * @return true if SSL errors should be ignored
     */
    public Boolean getInsecure() { return insecure; }

    /**
     * Sets whether to ignore SSL certificate errors.
     * @param insecure true if SSL errors should be ignored
     */
    public void setInsecure(Boolean insecure) { this.insecure = insecure; }

    /**
     * Gets the configuration for the provide goal.
     * @return the configuration for the provide goal
     */
    public ProvideConfig getProvide() { return provide; }

    /**
     * Sets the configuration for the provide goal.
     * @param provide the configuration for the provide goal
     */
    public void setProvide(ProvideConfig provide) { this.provide = provide; }

    /**
     * Gets the list of required service configurations.
     * @return the list of required service configurations
     */
    public List<RequireConfig> getRequires() { return requires; }

    /**
     * Sets the list of required service configurations.
     * @param requires the list of required service configurations
     */
    public void setRequires(List<RequireConfig> requires) { this.requires = requires; }

    /**
     * Configuration for uploading an OpenAPI specification.
     */
    public static class ProvideConfig {
        private String serviceName;
        private String openApiFile;

        /**
         * Gets the name of the service providing the API.
         * @return the name of the service providing the API
         */
        public String getServiceName() { return serviceName; }

        /**
         * Sets the name of the service providing the API.
         * @param serviceName the name of the service providing the API
         */
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        /**
         * Gets the path to the OpenAPI specification file.
         * @return the path to the OpenAPI specification file
         */
        public String getOpenApiFile() { return openApiFile; }

        /**
         * Sets the path to the OpenAPI specification file.
         * @param openApiFile the path to the OpenAPI specification file
         */
        public void setOpenApiFile(String openApiFile) { this.openApiFile = openApiFile; }
    }

    /**
     * Configuration for requiring endpoints from another service.
     */
    public static class RequireConfig {
        private String serviceName;
        private String outputDirectory;
        private Integer timeout;
        private List<EndpointConfig> endpoints;

        /**
         * Gets the name of the service being required.
         * @return the name of the service being required
         */
        public String getServiceName() { return serviceName; }

        /**
         * Sets the name of the service being required.
         * @param serviceName the name of the service being required
         */
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        /**
         * Gets the directory to save the downloaded snippets.
         * @return the directory to save the downloaded snippets
         */
        public String getOutputDirectory() { return outputDirectory; }

        /**
         * Sets the directory to save the downloaded snippets.
         * @param outputDirectory the directory to save the downloaded snippets
         */
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }

        /**
         * Gets the specific timeout for this requirement.
         * @return the specific timeout for this requirement
         */
        public Integer getTimeout() { return timeout; }

        /**
         * Sets the specific timeout for this requirement.
         * @param timeout the specific timeout for this requirement
         */
        public void setTimeout(Integer timeout) { this.timeout = timeout; }

        /**
         * Gets the list of endpoints to download.
         * @return the list of endpoints to download
         */
        public List<EndpointConfig> getEndpoints() { return endpoints; }

        /**
         * Sets the list of endpoints to download.
         * @param endpoints the list of endpoints to download
         */
        public void setEndpoints(List<EndpointConfig> endpoints) { this.endpoints = endpoints; }
    }

    /**
     * Configuration for a single API endpoint.
     */
    public static class EndpointConfig {
        private String method;
        private String path;

        /**
         * Gets the HTTP method (GET, POST, etc.).
         * @return the HTTP method (GET, POST, etc.)
         */
        public String getMethod() { return method; }

        /**
         * Sets the HTTP method (GET, POST, etc.).
         * @param method the HTTP method (GET, POST, etc.)
         */
        public void setMethod(String method) { this.method = method; }

        /**
         * Gets the API path.
         * @return the API path
         */
        public String getPath() { return path; }

        /**
         * Sets the API path.
         * @param path the API path
         */
        public void setPath(String path) { this.path = path; }
    }
}
