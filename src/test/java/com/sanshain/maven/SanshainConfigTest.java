package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SanshainConfigTest {

    @TempDir
    Path tempDir;

    private SanshainConfig load(String yaml) throws IOException, MojoExecutionException {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);
        return new SanshainConfig().loadConfig(configPath.toFile());
    }

    private MojoExecutionException loadExpectingError(String yaml) throws IOException {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);
        return assertThrows(MojoExecutionException.class, () ->
                new SanshainConfig().loadConfig(configPath.toFile()));
    }

    @Test
    public void testLoadFullConfig() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                "serviceName: my-service\n" +
                "compression: false\n" +
                "insecure: true\n" +
                "provide:\n" +
                "  openApiFile: src/main/resources/openapi.yaml\n" +
                "requires:\n" +
                "  - serviceName: other-service\n" +
                "    version: 2.3.0\n" +
                "    outputDirectory: target/generated\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n" +
                "      - method: POST\n" +
                "        path: /api/v1/users\n";
        SanshainConfig config = load(yaml);

        assertEquals("https://api.sanshain.com", config.getSanshainUrl());
        assertEquals("my-service", config.getServiceName());
        assertFalse(config.getCompression());
        assertTrue(config.getInsecure());

        assertNotNull(config.getProvide());
        assertEquals("src/main/resources/openapi.yaml", config.getProvide().getOpenApiFile());

        assertNotNull(config.getRequires());
        assertEquals(1, config.getRequires().size());
        SanshainConfig.RequireConfig req = config.getRequires().get(0);
        assertEquals("other-service", req.getServiceName());
        assertEquals("2.3.0", req.getVersion());
        assertEquals("target/generated", req.getOutputDirectory());
        assertEquals(2, req.getEndpoints().size());
        assertEquals("GET", req.getEndpoints().get(0).getMethod());
        assertEquals("/api/v1/users", req.getEndpoints().get(0).getPath());
        assertEquals("POST", req.getEndpoints().get(1).getMethod());
        assertEquals("/api/v1/users", req.getEndpoints().get(1).getPath());
    }

    @Test
    public void testLoadMinimalConfig() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                "clientName: test-client\n";
        SanshainConfig config = load(yaml);

        assertEquals("https://api.sanshain.com", config.getSanshainUrl());
        assertEquals("test-client", config.getClientName());
        assertNull(config.getCompression());
        assertNull(config.getProvide());
        assertNull(config.getRequires());
    }

    @Test
    public void testLoadConfigMissingFile() throws MojoExecutionException {
        File nonExistent = new File(tempDir.toFile(), "nonexistent.yaml");
        SanshainConfig config = new SanshainConfig().loadConfig(nonExistent);

        // Returns a default config when file doesn't exist
        assertNull(config.getSanshainUrl());
        assertNull(config.getClientName());
    }

    @Test
    public void testLoadConfigNullFile() throws MojoExecutionException {
        SanshainConfig config = new SanshainConfig().loadConfig(null);
        assertNull(config.getSanshainUrl());
    }

    @Test
    public void testLoadConfigInvalidYaml() throws IOException {
        String yaml = "invalid: [yaml: {broken";
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);

        assertThrows(MojoExecutionException.class, () ->
                new SanshainConfig().loadConfig(configPath.toFile()));
    }

    // --- Sanshain 2.0 parse-time validation ---

    @Test
    public void testRequireWithoutVersionIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    outputDirectory: out\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("requires[0] (user-service): missing 'version'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Sanshain 2.0 pins exact versions"), ex.getMessage());
        assertTrue(ex.getMessage().contains("add version: 1.2.0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GET /producers/user-service/versions"), ex.getMessage());
    }

    @Test
    public void testRequireBranchIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    branch: feature-x\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("requires[0] (user-service): 'branch' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("the branch model was removed in Sanshain 2.0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("replace with an exact 'version' pin"), ex.getMessage());
    }

    @Test
    public void testRequireTimeoutIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    timeout: 60\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("'timeout' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("fails fast (404)"), ex.getMessage());
    }

    @Test
    public void testGlobalTimeoutIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "timeout: 120\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("'timeout' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no long-polling"), ex.getMessage());
    }

    @Test
    public void testReleaseBranchesIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "releaseBranches:\n" +
                "  - main\n" +
                "  - master\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("'releaseBranches' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SANSHAIN_GA=true"), ex.getMessage());
    }

    @Test
    public void testProvideBaseVersionIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "provide:\n" +
                "  file: openapi.yaml\n" +
                "  baseVersion: 5\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("provide: 'baseVersion' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GA immutability replaced optimistic concurrency"), ex.getMessage());
    }

    @Test
    public void testProvideBranchIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "provides:\n" +
                "  - file: openapi.yaml\n" +
                "    branch: main\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("provides[0]: 'branch' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("info.version"), ex.getMessage());
    }

    @Test
    public void testProvideStabilityInYamlIsNamedError() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "provide:\n" +
                "  file: openapi.yaml\n" +
                "  stability: ga\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("'stability' is not configured in sanshain.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("-Dsanshain.ga=true"), ex.getMessage());
    }

    @Test
    public void testAllViolationsAreCollectedTogether() throws IOException {
        String yaml = "serviceName: test-client\n" +
                "timeout: 120\n" +
                "releaseBranches: [main]\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    branch: feature-x\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api\n";

        MojoExecutionException ex = loadExpectingError(yaml);
        assertTrue(ex.getMessage().contains("'timeout' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'releaseBranches' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'branch' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("missing 'version'"), ex.getMessage());
    }

    @Test
    public void testMultipleRequires() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                "clientName: test-client\n" +
                "requires:\n" +
                "  - serviceName: service-a\n" +
                "    version: 1.0.0\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/a\n" +
                "  - serviceName: service-b\n" +
                "    version: 2.1.0\n" +
                "    endpoints:\n" +
                "      - method: POST\n" +
                "        path: /api/b\n";
        SanshainConfig config = load(yaml);

        assertEquals(2, config.getRequires().size());
        assertEquals("service-a", config.getRequires().get(0).getServiceName());
        assertEquals("1.0.0", config.getRequires().get(0).getVersion());
        assertEquals("service-b", config.getRequires().get(1).getServiceName());
        assertEquals("2.1.0", config.getRequires().get(1).getVersion());
    }

    @Test
    public void testSettersAndGetters() {
        SanshainConfig config = new SanshainConfig();
        config.setSanshainUrl("https://test.com");
        config.setClientName("client");
        config.setCompression(true);
        config.setInsecure(true);

        assertEquals("https://test.com", config.getSanshainUrl());
        assertEquals("client", config.getClientName());
        assertTrue(config.getCompression());
        assertTrue(config.getInsecure());

        SanshainConfig.ProvideConfig provide = new SanshainConfig.ProvideConfig();
        provide.setOpenApiFile("api.yaml");
        config.setProvide(provide);
        assertEquals("api.yaml", config.getProvide().getOpenApiFile());

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/test");
        assertEquals("GET", ep.getMethod());
        assertEquals("/test", ep.getPath());

        SanshainConfig.RequireConfig req = new SanshainConfig.RequireConfig();
        req.setServiceName("other");
        req.setVersion("1.2.3");
        req.setOutputDirectory("out");
        req.setEndpoints(java.util.List.of(ep));
        assertEquals("other", req.getServiceName());
        assertEquals("1.2.3", req.getVersion());
        assertEquals("out", req.getOutputDirectory());
        assertEquals(1, req.getEndpoints().size());

        config.setRequires(java.util.List.of(req));
        assertEquals(1, config.getRequires().size());
    }

    @Test
    public void testCombineConfiguration() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                "clientName: test-client\n" +
                "combine: true\n" +
                "provide:\n" +
                "  file: src/main/resources/openapi.yaml\n" +
                "  combine: false\n" +
                "provides:\n" +
                "  - file: src/main/resources/asyncapi.yaml\n" +
                "    combine: true\n";
        SanshainConfig config = load(yaml);

        assertTrue(config.getCombine());
        assertNotNull(config.getProvide());
        assertFalse(config.getProvide().getCombine());

        assertNotNull(config.getProvides());
        assertEquals(1, config.getProvides().size());
        assertTrue(config.getProvides().get(0).getCombine());

        SanshainMojoDelegate delegate = new SanshainMojoDelegate(null, null, null, false);

        // Per-item override
        assertTrue(delegate.resolveCombine(config.getProvides().get(0).getCombine(), null, config));
        assertFalse(delegate.resolveCombine(config.getProvide().getCombine(), null, config));

        // Falls back to global config
        assertTrue(delegate.resolveCombine(null, null, config));

        // Defaults to true when everything is null
        assertTrue(delegate.resolveCombine(null, null, null));
    }
}
