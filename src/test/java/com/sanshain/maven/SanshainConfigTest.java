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

    @Test
    public void testLoadFullConfig() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                "clientName: test-client\n" +
                "timeout: 60\n" +
                "compression: false\n" +
                "provide:\n" +
                "  serviceName: my-service\n" +
                "  openApiFile: src/main/resources/openapi.yaml\n" +
                "requires:\n" +
                "  - serviceName: other-service\n" +
                "    outputDirectory: target/generated\n" +
                "    timeout: 90\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n" +
                "      - method: POST\n" +
                "        path: /api/v1/users\n";
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);

        SanshainConfig config = new SanshainConfig().loadConfig(configPath.toFile());

        assertEquals("https://api.sanshain.com", config.getSanshainUrl());
        assertEquals("test-client", config.getClientName());
        assertEquals(60, config.getTimeout());
        assertFalse(config.getCompression());

        assertNotNull(config.getProvide());
        assertEquals("my-service", config.getProvide().getServiceName());
        assertEquals("src/main/resources/openapi.yaml", config.getProvide().getOpenApiFile());

        assertNotNull(config.getRequires());
        assertEquals(1, config.getRequires().size());
        SanshainConfig.RequireConfig req = config.getRequires().get(0);
        assertEquals("other-service", req.getServiceName());
        assertEquals("target/generated", req.getOutputDirectory());
        assertEquals(90, req.getTimeout());
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
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);

        SanshainConfig config = new SanshainConfig().loadConfig(configPath.toFile());

        assertEquals("https://api.sanshain.com", config.getSanshainUrl());
        assertEquals("test-client", config.getClientName());
        assertNull(config.getTimeout());
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

    @Test
    public void testMultipleRequires() throws IOException, MojoExecutionException {
        String yaml = "sanshainUrl: https://api.sanshain.com\n" +
                "clientName: test-client\n" +
                "requires:\n" +
                "  - serviceName: service-a\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/a\n" +
                "  - serviceName: service-b\n" +
                "    endpoints:\n" +
                "      - method: POST\n" +
                "        path: /api/b\n";
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);

        SanshainConfig config = new SanshainConfig().loadConfig(configPath.toFile());

        assertEquals(2, config.getRequires().size());
        assertEquals("service-a", config.getRequires().get(0).getServiceName());
        assertEquals("service-b", config.getRequires().get(1).getServiceName());
    }

    @Test
    public void testSettersAndGetters() {
        SanshainConfig config = new SanshainConfig();
        config.setSanshainUrl("https://test.com");
        config.setClientName("client");
        config.setTimeout(30);
        config.setCompression(true);

        assertEquals("https://test.com", config.getSanshainUrl());
        assertEquals("client", config.getClientName());
        assertEquals(30, config.getTimeout());
        assertTrue(config.getCompression());

        SanshainConfig.ProvideConfig provide = new SanshainConfig.ProvideConfig();
        provide.setServiceName("svc");
        provide.setOpenApiFile("api.yaml");
        config.setProvide(provide);
        assertEquals("svc", config.getProvide().getServiceName());
        assertEquals("api.yaml", config.getProvide().getOpenApiFile());

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/test");
        assertEquals("GET", ep.getMethod());
        assertEquals("/test", ep.getPath());

        SanshainConfig.RequireConfig req = new SanshainConfig.RequireConfig();
        req.setServiceName("other");
        req.setOutputDirectory("out");
        req.setTimeout(45);
        req.setEndpoints(java.util.List.of(ep));
        assertEquals("other", req.getServiceName());
        assertEquals("out", req.getOutputDirectory());
        assertEquals(45, req.getTimeout());
        assertEquals(1, req.getEndpoints().size());

        config.setRequires(java.util.List.of(req));
        assertEquals(1, config.getRequires().size());
    }
}
