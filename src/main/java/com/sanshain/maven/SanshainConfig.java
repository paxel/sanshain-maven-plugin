package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration object for the Sanshain plugin.
 * Maps directly to the sanshain.yaml structure (Sanshain 2.0 shape).
 *
 * <p>Branch-era fields ({@code branch}, {@code timeout}, {@code baseVersion},
 * {@code releaseBranches}, {@code stability}) are still parsed, but only so that
 * {@link #validate()} can reject them by name with a migration hint instead of
 * failing with a generic unknown-field error.</p>
 */
public class SanshainConfig {

    /** Creates a new default configuration instance. */
    public SanshainConfig() {}

    private String sanshainUrl;
    private String serviceName;
    private Boolean compression;
    private Boolean insecure;
    private Boolean bestEffort;
    private Boolean combine;
    private ProvideConfig provide;
    @JsonProperty("provides")
    private List<ProvideConfig> provides;
    @JsonProperty("requires")
    private List<RequireConfig> requires;

    // Legacy 1.x fields — parse targets for named validation errors only.
    private Integer timeout;
    private Object releaseBranches;

    /**
     * Loads the Sanshain configuration from the specified file, applies environment variable
     * overrides, and validates it against the Sanshain 2.0 configuration shape. Validation
     * happens at parse time, before any network call: a requires entry without {@code version}
     * and every leftover branch-era field are hard errors with a migration hint.
     *
     * @param configFile the YAML configuration file
     * @return the loaded and resolved {@link SanshainConfig}
     * @throws MojoExecutionException if loading, parsing, or validation fails
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
        config.validate();
        return config;
    }

    /**
     * Validates the configuration against the Sanshain 2.0 shape. Collects all violations and
     * reports them together, each with a migration hint.
     *
     * @throws MojoExecutionException if the configuration uses removed 1.x fields or a requires
     *                                entry is missing its version pin
     */
    void validate() throws MojoExecutionException {
        List<String> errors = new ArrayList<>();

        if (timeout != null) {
            errors.add("'timeout' is no longer supported — Sanshain 2.0 resolves requires immediately "
                    + "(no long-polling); remove it");
        }
        if (releaseBranches != null) {
            errors.add("'releaseBranches' is no longer supported — the branch model was removed in Sanshain 2.0; "
                    + "stability is declared per build via -Dsanshain.ga=true / SANSHAIN_GA=true (default: snapshot)");
        }

        if (provide != null) {
            validateProvide(provide, "provide", errors);
        }
        if (provides != null) {
            for (int i = 0; i < provides.size(); i++) {
                validateProvide(provides.get(i), "provides[" + i + "]", errors);
            }
        }

        if (requires != null) {
            for (int i = 0; i < requires.size(); i++) {
                RequireConfig req = requires.get(i);
                String label = "requires[" + i + "]" + (req.getServiceName() != null ? " (" + req.getServiceName() + ")" : "");
                if (req.branch != null) {
                    errors.add(label + ": 'branch' is no longer supported — the branch model was removed in "
                            + "Sanshain 2.0; replace with an exact 'version' pin");
                }
                if (req.timeout != null) {
                    errors.add(label + ": 'timeout' is no longer supported — Sanshain 2.0 resolves immediately; "
                            + "a missing pinned version fails fast (404); remove it");
                }
                if (req.getVersion() == null) {
                    String producer = req.getServiceName() != null ? req.getServiceName() : "<producer>";
                    errors.add(label + ": missing 'version' — Sanshain 2.0 pins exact versions; "
                            + "add version: 1.2.0 (list available: GET /producers/" + producer + "/versions)");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new MojoExecutionException("Invalid sanshain.yaml for Sanshain 2.0:\n - "
                    + String.join("\n - ", errors));
        }
    }

    private void validateProvide(ProvideConfig p, String label, List<String> errors) {
        if (p.branch != null) {
            errors.add(label + ": 'branch' is no longer supported — the branch model was removed in Sanshain 2.0; "
                    + "the version is read from the spec file (info.version / // sanshain-version:)");
        }
        if (p.baseVersion != null) {
            errors.add(label + ": 'baseVersion' is no longer supported — GA immutability replaced optimistic "
                    + "concurrency in Sanshain 2.0; remove it");
        }
        if (p.stability != null) {
            errors.add(label + ": 'stability' is not configured in sanshain.yaml — declare it per build via "
                    + "-Dsanshain.ga=true / SANSHAIN_GA=true (default: snapshot)");
        }
    }

    private void applyEnvOverrides(SanshainConfig config) {
        config.setSanshainUrl(getEnv("SANSHAIN_URL", config.getSanshainUrl()));

        String serviceNameEnv = getEnv("SANSHAIN_SERVICE_NAME", getEnv("SANSHAIN_CLIENT_NAME", null));
        if (serviceNameEnv != null) {
            config.setServiceName(serviceNameEnv);
        }

        String compressionEnv = System.getenv("SANSHAIN_COMPRESSION");
        if (compressionEnv != null) {
            config.setCompression(Boolean.parseBoolean(compressionEnv));
        }

        String insecureEnv = System.getenv("SANSHAIN_INSECURE");
        if (insecureEnv != null) {
            config.setInsecure(Boolean.parseBoolean(insecureEnv));
        }

        String bestEffortEnv = System.getenv("SANSHAIN_BEST_EFFORT");
        if (bestEffortEnv != null) {
            config.setBestEffort(Boolean.parseBoolean(bestEffortEnv));
        }

        String combineEnv = System.getenv("SANSHAIN_COMBINE");
        if (combineEnv != null) {
            config.setCombine(Boolean.parseBoolean(combineEnv));
        }
    }

    private String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
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
     * Gets the name of the service/client.
     * @return the name of the service
     */
    public String getServiceName() { return serviceName; }

    /**
     * Sets the name of the service/client.
     * @param serviceName the name of the service
     */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    /**
     * Gets the name of the client (alias for serviceName).
     * @return the name of the client
     */
    @JsonProperty("clientName")
    public String getClientName() { return serviceName; }

    /**
     * Sets the name of the client (alias for serviceName).
     * @param clientName the name of the client
     */
    @JsonProperty("clientName")
    public void setClientName(String clientName) { this.serviceName = clientName; }

    /**
     * Legacy 1.x long-polling timeout. Parse target only; rejected by {@link #validate()}.
     * @param timeout the removed timeout value
     */
    @JsonProperty("timeout")
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    /**
     * Legacy 1.x release-branch list. Parse target only; rejected by {@link #validate()}.
     * @param releaseBranches the removed releaseBranches value
     */
    @JsonProperty("releaseBranches")
    public void setReleaseBranches(Object releaseBranches) { this.releaseBranches = releaseBranches; }

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
     * Gets whether to continue on Sanshain errors.
     * @return true if errors should be logged as warnings
     */
    public Boolean getBestEffort() { return bestEffort; }

    /**
     * Sets whether to continue on Sanshain errors.
     * @param bestEffort true if errors should be logged as warnings
     */
    public void setBestEffort(Boolean bestEffort) { this.bestEffort = bestEffort; }

    /**
     * Gets whether to recursively resolve and inline relative references/imports.
     * @return true if files should be combined
     */
    public Boolean getCombine() { return combine; }

    /**
     * Sets whether to recursively resolve and inline relative references/imports.
     * @param combine true if files should be combined
     */
    public void setCombine(Boolean combine) { this.combine = combine; }

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
     * Gets the list of provided service configurations.
     * @return the list of provided service configurations
     */
    public List<ProvideConfig> getProvides() { return provides; }

    /**
     * Sets the list of provided service configurations.
     * @param provides the list of provided service configurations
     */
    public void setProvides(List<ProvideConfig> provides) { this.provides = provides; }

    /**
     * Configuration for uploading an API specification. The version of a Provide is never
     * configured here — it is read from the spec file itself ({@code info.version}, or a
     * {@code // sanshain-version:} comment for proto).
     */
    public static class ProvideConfig {

        /** Creates a new default provide configuration. */
        public ProvideConfig() {}

        private String file;
        private String apiType;
        private Boolean combine;
        private boolean retired;

        // Legacy 1.x fields — parse targets for named validation errors only.
        private String branch;
        private Integer baseVersion;
        private String stability;

        // Backward compatibility fields
        private String openApiFile;
        private String asyncApiFile;
        private String protoFile;

        /**
         * Gets the path to the specification file.
         * @return the path to the specification file
         */
        public String getFile() { return file; }

        /**
         * Sets the path to the specification file.
         * @param file the path to the specification file
         */
        public void setFile(String file) { this.file = file; }

        /**
         * Gets the type of API (openapi, asyncapi, proto).
         * @return the type of API
         */
        public String getApiType() { return apiType; }

        /**
         * Sets the type of API (openapi, asyncapi, proto).
         * @param apiType the type of API
         */
        public void setApiType(String apiType) { this.apiType = apiType; }

        /**
         * Whether this project has stopped providing the family.
         *
         * <p>Deleting the entry would not say so: Sanshain cannot tell a dropped
         * protocol from a pipeline that merely stopped running, so absence means
         * nothing and the capability, graph edges and contracts would linger.
         * Keeping the entry and marking it retired is the explicit act.
         *
         * @return true if the family is retired rather than provided
         */
        public boolean isRetired() { return retired; }

        /**
         * Marks the family as no longer provided. See {@link #isRetired()}.
         * @param retired true to retire the family
         */
        public void setRetired(boolean retired) { this.retired = retired; }

        /**
         * Legacy 1.x branch. Parse target only; rejected by validation.
         * @param branch the removed branch value
         */
        public void setBranch(String branch) { this.branch = branch; }

        /**
         * Legacy 1.x optimistic-concurrency base version. Parse target only; rejected by validation.
         * @param baseVersion the removed baseVersion value
         */
        public void setBaseVersion(Integer baseVersion) { this.baseVersion = baseVersion; }

        /**
         * Legacy stability-in-yaml. Parse target only; rejected by validation — stability is
         * declared per build via {@code -Dsanshain.ga=true} / {@code SANSHAIN_GA=true}.
         * @param stability the removed stability value
         */
        public void setStability(String stability) { this.stability = stability; }

        /**
         * Gets the path to the OpenAPI specification file (backward compatibility).
         * @return the path to the OpenAPI specification file
         */
        public String getOpenApiFile() { return openApiFile; }

        /**
         * Sets the path to the OpenAPI specification file (backward compatibility).
         * @param openApiFile the path to the OpenAPI specification file
         */
        public void setOpenApiFile(String openApiFile) { this.openApiFile = openApiFile; }

        /**
         * Gets the path to the AsyncAPI specification file (backward compatibility).
         * @return the path to the AsyncAPI specification file
         */
        public String getAsyncApiFile() { return asyncApiFile; }

        /**
         * Sets the path to the AsyncAPI specification file (backward compatibility).
         * @param asyncApiFile the path to the AsyncAPI specification file
         */
        public void setAsyncApiFile(String asyncApiFile) { this.asyncApiFile = asyncApiFile; }

        /**
         * Gets the path to the Protocol Buffers specification file (backward compatibility).
         * @return the path to the Protocol Buffers specification file
         */
        public String getProtoFile() { return protoFile; }

        /**
         * Sets the path to the Protocol Buffers specification file (backward compatibility).
         * @param protoFile the path to the Protocol Buffers specification file
         */
        public void setProtoFile(String protoFile) { this.protoFile = protoFile; }

        /**
         * Gets whether to recursively resolve and inline relative references/imports.
         * @return true if files should be combined
         */
        public Boolean getCombine() { return combine; }

        /**
         * Sets whether to recursively resolve and inline relative references/imports.
         * @param combine true if files should be combined
         */
        public void setCombine(Boolean combine) { this.combine = combine; }
    }

    /**
     * Configuration for requiring endpoints from another service at an exact pinned version.
     */
    public static class RequireConfig {

        /** Creates a new default require configuration. */
        public RequireConfig() {}

        private String serviceName;
        private String apiType;
        private String version;
        private String outputDirectory;
        private List<EndpointConfig> endpoints;

        // Legacy 1.x fields — parse targets for named validation errors only.
        private String branch;
        private Integer timeout;

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
         * Gets the type of API being required (openapi, asyncapi, proto).
         * @return the type of API being required
         */
        public String getApiType() { return apiType; }

        /**
         * Sets the type of API being required (openapi, asyncapi, proto).
         * @param apiType the type of API being required
         */
        public void setApiType(String apiType) { this.apiType = apiType; }

        /**
         * Gets the exact pinned version ({@code MAJOR.MINOR.PATCH}) to require.
         * @return the pinned version
         */
        public String getVersion() { return version; }

        /**
         * Sets the exact pinned version ({@code MAJOR.MINOR.PATCH}) to require.
         * @param version the pinned version
         */
        public void setVersion(String version) { this.version = version; }

        /**
         * Legacy 1.x branch. Parse target only; rejected by validation.
         * @param branch the removed branch value
         */
        public void setBranch(String branch) { this.branch = branch; }

        /**
         * Legacy 1.x long-polling timeout. Parse target only; rejected by validation.
         * @param timeout the removed timeout value
         */
        public void setTimeout(Integer timeout) { this.timeout = timeout; }

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

        /** Creates a new default endpoint configuration. */
        public EndpointConfig() {}

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
