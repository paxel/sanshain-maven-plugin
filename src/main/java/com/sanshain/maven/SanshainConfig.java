package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Root configuration object for the Sanshain plugin.
 * Maps directly to the sanshain.yaml structure (Sanshain 2.0 shape).
 *
 * <p>Branch-era fields ({@code branch}, {@code timeout}, {@code baseVersion},
 * {@code releaseBranches}, {@code stability}) are still parsed, but only so that
 * {@link #loadConfig(File, SanshainMojoDelegate)} can reject them by name with a migration hint.</p>
 *
 * <p>Any other field this shape does not know is not an error: it is ignored and listed in
 * {@link #getUnknownFields()}, so the Mojo can name it (and fail under strict).</p>
 */
public class SanshainConfig {

    /** Creates a new default configuration instance. */
    public SanshainConfig() {}

    private String sanshainUrl;
    private String serviceName;
    private Boolean compression;
    private Boolean insecure;
    private Boolean bestEffort;
    private Boolean strict;
    private Boolean combine;
    private String catalogInfo;
    private ProvideConfig provide;
    @JsonProperty("provides")
    private List<ProvideConfig> provides;
    @JsonProperty("requires")
    private List<RequireConfig> requires;

    // Legacy 1.x fields — parse targets for named validation errors only.
    private Integer timeout;
    private Object releaseBranches;

    /** The directory containing sanshain.yaml; every relative path in it resolves against this. */
    @JsonIgnore
    private File configDirectory;

    /** Fields of sanshain.yaml no Sanshain client knows, as paths in file order. */
    @JsonIgnore
    private List<String> unknownFields = Collections.emptyList();

    // The union of the fields every Sanshain client knows. A field known here but not used by
    // this plugin is accepted silently; the legacy names are rejected by name instead.
    private static final Set<String> KNOWN_TOP = new HashSet<>(Arrays.asList(
            "sanshainUrl", "serviceName", "clientName", "compression", "bestEffort", "strict", "force",
            "insecure", "combine", "catalogInfo", "provide", "provides", "requires",
            "timeout", "releaseBranches"));
    private static final Set<String> KNOWN_PROVIDE = new HashSet<>(Arrays.asList(
            "file", "apiType", "retired", "openApiFile", "asyncApiFile", "protoFile", "combine",
            "branch", "baseVersion", "stability"));
    private static final Set<String> KNOWN_REQUIRE = new HashSet<>(Arrays.asList(
            "serviceName", "apiType", "version", "format", "outputDirectory", "endpoints",
            "branch", "timeout"));
    private static final Set<String> KNOWN_ENDPOINT = new HashSet<>(Arrays.asList(
            "method", "path"));

    /** The environment the overrides are read from; replaceable in tests. */
    @JsonIgnore
    private Map<String, String> environmentVariables = System.getenv();

    /**
     * Loads the Sanshain configuration from the specified file, applies environment variable
     * overrides, and validates it against the Sanshain 2.0 configuration shape. Unknown fields are
     * recorded in {@link #getUnknownFields()} without being reported.
     *
     * @param configFile the YAML configuration file
     * @return the loaded and resolved {@link SanshainConfig}
     * @throws MojoExecutionException if loading, parsing, or validation fails
     */
    public SanshainConfig loadConfig(File configFile) throws MojoExecutionException {
        try {
            return loadConfig(configFile, null);
        } catch (MojoFailureException e) {
            // Unreachable: only a reporter fails on unknown fields.
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Loads the Sanshain configuration from the specified file, applies environment variable
     * overrides, and validates it against the Sanshain 2.0 configuration shape, before any
     * network call. As soon as the file parses as YAML: first every leftover branch-era field is
     * rejected by name with a migration hint, then the reporter is told about unknown fields (and
     * fails under strict), and only then the remaining checks run (a requires entry without
     * {@code version}). So a typo is named even when it causes a later validation error.
     *
     * @param configFile the YAML configuration file
     * @param reporter   names unknown fields (the puzzled cat); null to only record them
     * @return the loaded and resolved {@link SanshainConfig}
     * @throws MojoExecutionException if loading, parsing, or validation fails
     * @throws MojoFailureException   if the reporter rejects unknown fields (strict)
     */
    public SanshainConfig loadConfig(File configFile, SanshainMojoDelegate reporter)
            throws MojoExecutionException, MojoFailureException {
        if (configFile == null || !configFile.exists()) {
            configDirectory = configDirectoryOf(configFile);
            applyEnvOverrides(this);
            return this;
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode tree;
        try {
            tree = mapper.readTree(configFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load configuration from " + configFile.getAbsolutePath(), e);
        }
        List<String> unknown = findUnknownFields(tree);

        SanshainConfig config;
        try {
            config = mapper.readValue(configFile, SanshainConfig.class);
        } catch (IOException e) {
            // The YAML parsed but does not bind: still name the unknown fields first.
            report(reporter, unknown);
            throw new MojoExecutionException("Failed to load configuration from " + configFile.getAbsolutePath(), e);
        }
        config.unknownFields = unknown;
        if (reporter != null) {
            reporter.resolveStrict(config);
        }
        config.configDirectory = configDirectoryOf(configFile);
        applyEnvOverrides(config);

        List<String> legacyErrors = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        config.collectViolations(legacyErrors, errors);
        if (!legacyErrors.isEmpty()) {
            throw invalid(errors);
        }
        report(reporter, unknown);
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return config;
    }

    private static void report(SanshainMojoDelegate reporter, List<String> unknown) throws MojoFailureException {
        if (reporter != null) {
            reporter.reportUnknownFields(unknown);
        }
    }

    private static MojoExecutionException invalid(List<String> errors) {
        return new MojoExecutionException("Invalid sanshain.yaml for Sanshain 2.0:\n - "
                + String.join("\n - ", errors));
    }

    /**
     * Lists every field the known sanshain.yaml shape does not have, as a path such as
     * {@code catalogInf}, {@code provides[1].verison} or {@code requires[0].endpoints[2].methd},
     * in file order.
     */
    private static List<String> findUnknownFields(JsonNode root) {
        List<String> unknown = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return unknown;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (!KNOWN_TOP.contains(name)) {
                unknown.add(name);
            } else if (name.equals("provide")) {
                collectUnknown(value, "provide.", KNOWN_PROVIDE, unknown);
            } else if (name.equals("provides") && value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    collectUnknown(value.get(i), "provides[" + i + "].", KNOWN_PROVIDE, unknown);
                }
            } else if (name.equals("requires") && value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    collectUnknown(value.get(i), "requires[" + i + "].", KNOWN_REQUIRE, unknown);
                }
            }
        }
        return unknown;
    }

    private static void collectUnknown(JsonNode node, String prefix, Set<String> known, List<String> unknown) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (!known.contains(name)) {
                unknown.add(prefix + name);
            } else if (known == KNOWN_REQUIRE && name.equals("endpoints") && field.getValue().isArray()) {
                JsonNode endpoints = field.getValue();
                for (int j = 0; j < endpoints.size(); j++) {
                    collectUnknown(endpoints.get(j), prefix + "endpoints[" + j + "].", KNOWN_ENDPOINT, unknown);
                }
            }
        }
    }

    private static File configDirectoryOf(File configFile) {
        if (configFile == null) {
            return new File(".").getAbsoluteFile();
        }
        File parent = configFile.getAbsoluteFile().getParentFile();
        return parent != null ? parent : new File(".").getAbsoluteFile();
    }

    /**
     * Resolves a path written in sanshain.yaml. A relative path resolves against the directory
     * containing sanshain.yaml, never the working directory; an absolute path stays absolute.
     *
     * @param path the path as written in sanshain.yaml
     * @return the resolved file
     */
    public File resolvePath(String path) {
        File base = configDirectory != null ? configDirectory : new File(".").getAbsoluteFile();
        return base.toPath().resolve(path).toFile();
    }

    /**
     * Checks the configuration against the Sanshain 2.0 shape, each violation with a migration
     * hint. {@code errors} receives every violation in file order; {@code legacyErrors} only the
     * removed 1.x fields among them.
     *
     * @param legacyErrors receives the removed-field violations
     * @param errors       receives all violations
     */
    private void collectViolations(List<String> legacyErrors, List<String> errors) {
        if (timeout != null) {
            legacy(legacyErrors, errors, "'timeout' is no longer supported — Sanshain 2.0 resolves requires immediately "
                    + "(no long-polling); remove it");
        }
        if (releaseBranches != null) {
            legacy(legacyErrors, errors, "'releaseBranches' is no longer supported — the branch model was removed in Sanshain 2.0; "
                    + "stability is declared per build via -Dsanshain.ga=true / SANSHAIN_GA=true (default: snapshot)");
        }

        if (provide != null) {
            validateProvide(provide, "provide", legacyErrors, errors);
        }
        if (provides != null) {
            for (int i = 0; i < provides.size(); i++) {
                validateProvide(provides.get(i), "provides[" + i + "]", legacyErrors, errors);
            }
        }

        if (requires != null) {
            for (int i = 0; i < requires.size(); i++) {
                RequireConfig req = requires.get(i);
                String label = "requires[" + i + "]" + (req.getServiceName() != null ? " (" + req.getServiceName() + ")" : "");
                if (req.branch != null) {
                    legacy(legacyErrors, errors, label + ": 'branch' is no longer supported — the branch model was removed in "
                            + "Sanshain 2.0; replace with an exact 'version' pin");
                }
                if (req.timeout != null) {
                    legacy(legacyErrors, errors, label + ": 'timeout' is no longer supported — Sanshain 2.0 resolves immediately; "
                            + "a missing pinned version fails fast (404); remove it");
                }
                if (req.getVersion() == null) {
                    String producer = req.getServiceName() != null ? req.getServiceName() : "<producer>";
                    errors.add(label + ": missing 'version' — Sanshain 2.0 pins exact versions; "
                            + "add version: 1.2.0 (list available: GET /producers/" + producer + "/versions)");
                }
                if (req.getFormat() != null) {
                    if (!"openapi".equals(req.getFormat())) {
                        errors.add(label + ": format '" + req.getFormat() + "' is not supported — the only "
                                + "allowed combination is apiType: asyncapi with format: openapi");
                    } else if (!"asyncapi".equalsIgnoreCase(req.getApiType())) {
                        errors.add(label + ": format: openapi is only valid with apiType: asyncapi"
                                + (req.getApiType() != null ? " (not " + req.getApiType() + ")" : "")
                                + " — the only allowed combination is apiType: asyncapi with format: openapi");
                    }
                }
            }
        }
    }

    private static void legacy(List<String> legacyErrors, List<String> errors, String message) {
        legacyErrors.add(message);
        errors.add(message);
    }

    private void validateProvide(ProvideConfig p, String label, List<String> legacyErrors, List<String> errors) {
        if (p.branch != null) {
            legacy(legacyErrors, errors, label + ": 'branch' is no longer supported — the branch model was removed in Sanshain 2.0; "
                    + "the version is read from the spec file (info.version / // sanshain-version:)");
        }
        if (p.baseVersion != null) {
            legacy(legacyErrors, errors, label + ": 'baseVersion' is no longer supported — GA immutability replaced optimistic "
                    + "concurrency in Sanshain 2.0; remove it");
        }
        if (p.stability != null) {
            legacy(legacyErrors, errors, label + ": 'stability' is not configured in sanshain.yaml — declare it per build via "
                    + "-Dsanshain.ga=true / SANSHAIN_GA=true (default: snapshot)");
        }
    }

    private void applyEnvOverrides(SanshainConfig config) {
        config.setSanshainUrl(getEnv("SANSHAIN_URL", config.getSanshainUrl()));

        String serviceNameEnv = getEnv("SANSHAIN_SERVICE_NAME", getEnv("SANSHAIN_CLIENT_NAME", null));
        if (serviceNameEnv != null) {
            config.setServiceName(serviceNameEnv);
        }

        String compressionEnv = getEnv("SANSHAIN_COMPRESSION", null);
        if (compressionEnv != null) {
            config.setCompression(SanshainMojoDelegate.isSwitchOn(compressionEnv));
        }

        String insecureEnv = getEnv("SANSHAIN_INSECURE", null);
        if (insecureEnv != null) {
            config.setInsecure(SanshainMojoDelegate.isSwitchOn(insecureEnv));
        }

        String bestEffortEnv = getEnv("SANSHAIN_BEST_EFFORT", null);
        if (bestEffortEnv != null) {
            config.setBestEffort(SanshainMojoDelegate.isSwitchOn(bestEffortEnv));
        }

        String combineEnv = getEnv("SANSHAIN_COMBINE", null);
        if (combineEnv != null) {
            config.setCombine(SanshainMojoDelegate.isSwitchOn(combineEnv));
        }
    }

    private String getEnv(String name, String defaultValue) {
        String value = environmentVariables.get(name);
        return value != null ? value : defaultValue;
    }

    @JsonIgnore
    void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
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
     * Legacy 1.x long-polling timeout. Parse target only; rejected by {@link #loadConfig(File, SanshainMojoDelegate)}.
     * @param timeout the removed timeout value
     */
    @JsonProperty("timeout")
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    /**
     * Legacy 1.x release-branch list. Parse target only; rejected by {@link #loadConfig(File, SanshainMojoDelegate)}.
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
     * Gets the yaml strict switch: fail on missing configuration and unknown fields.
     * An explicitly set {@code sanshain.strict} property overrides it.
     * @return the yaml strict value, or null if not set
     */
    public Boolean getStrict() { return strict; }

    /**
     * Sets the yaml strict switch.
     * @param strict true to fail on missing configuration and unknown fields
     */
    public void setStrict(Boolean strict) { this.strict = strict; }

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
     * Gets the path to the Backstage {@code catalog-info.yaml} pushed by the catalog goal,
     * relative to the directory containing sanshain.yaml.
     * @return the catalogInfo path, or null if not configured
     */
    public String getCatalogInfo() { return catalogInfo; }

    /**
     * Sets the path to the Backstage {@code catalog-info.yaml} pushed by the catalog goal.
     * @param catalogInfo the catalogInfo path
     */
    public void setCatalogInfo(String catalogInfo) { this.catalogInfo = catalogInfo; }

    /**
     * Fields of sanshain.yaml that no Sanshain client knows, as paths in file order
     * (e.g. {@code catalogInf}, {@code requires[0].verison}).
     * @return the unknown field paths; empty when there are none
     */
    @JsonIgnore
    public List<String> getUnknownFields() { return unknownFields; }

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
        private String format;
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
         * Gets the format to receive the spec in: {@code openapi} turns an AsyncAPI require into
         * an OpenAPI document (needs Sanshain Service 2.4.0). Only valid with apiType asyncapi.
         * @return the requested format, or null for the native one
         */
        public String getFormat() { return format; }

        /**
         * Sets the format to receive the spec in (only {@code openapi}, with apiType asyncapi).
         * @param format the requested format
         */
        public void setFormat(String format) { this.format = format; }

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
