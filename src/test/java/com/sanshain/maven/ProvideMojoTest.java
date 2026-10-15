package com.sanshain.maven;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class ProvideMojoTest {

    @TempDir
    Path tempDir;

    private WireMockServer wireMock;
    private String baseUrl;

    @BeforeEach
    public void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
    }

    @AfterEach
    public void tearDown() {
        wireMock.stop();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), fieldName);
            }
            throw e;
        }
    }

    private ProvideMojo createMojo(String yamlContent, String openapiContent) throws Exception {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yamlContent);

        Path openapiPath = tempDir.resolve("openapi.yaml");
        Files.writeString(openapiPath, openapiContent);

        ProvideMojo mojo = new ProvideMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", configPath.toFile());
        setField(mojo, "openApiFile", openapiPath.toFile());
        setField(mojo, "sanshainUrl", baseUrl);
        setField(mojo, "serviceName", "my-service");
        setField(mojo, "token", null);
        setField(mojo, "compression", false);
        setField(mojo, "serverId", "sanshain");
        setField(mojo, "settings", null);
        return mojo;
    }

    private SanshainMojoDelegate createDelegate() {
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.monitor.logging.DefaultLog(new org.codehaus.plexus.logging.console.ConsoleLogger());
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, false);
        delegate.setEnvironmentVariables(Collections.emptyMap());
        return delegate;
    }

    @Test
    public void testExecuteSuccess() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test\n  version: 1.0.0");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.producername", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.stability", equalTo("snapshot")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: \"3.0.0\""))));
    }

    @Test
    public void testProvidePayloadCarriesNoBranchEraFields() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  version: 1.0.0");
        mojo.execute();

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertFalse(body.contains("\"branch\""), "branch must not be sent in 2.0: " + body);
        assertFalse(body.contains("\"base_version\""), "base_version must not be sent in 2.0: " + body);
        assertFalse(body.contains("\"force\""), "force must not be sent in 2.0: " + body);
        assertFalse(body.contains("\"source_protected_branch\""), "source_protected_branch must not be sent in 2.0: " + body);
        assertFalse(body.contains("\"author\""), "author was removed from the 2.0 contract: " + body);
        assertTrue(body.contains("\"stability\":\"snapshot\""), "stability must be declared: " + body);
    }

    @Test
    public void testGaPropertySendsGaStability() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  version: 1.0.0");
        setField(mojo, "ga", true);
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.stability", equalTo("ga"))));
    }

    // --- GA switch precedence (delegate level) ---

    @Test
    public void testResolveStabilityDefaultIsSnapshot() {
        SanshainMojoDelegate delegate = createDelegate();
        assertEquals("snapshot", delegate.resolveStability(false));
    }

    @Test
    public void testResolveStabilityGaProperty() {
        SanshainMojoDelegate delegate = createDelegate();
        assertEquals("ga", delegate.resolveStability(true));
    }

    @Test
    public void testResolveStabilityFromEnvVariable() {
        SanshainMojoDelegate delegate = createDelegate();
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_GA", "true");
        delegate.setEnvironmentVariables(env);
        assertEquals("ga", delegate.resolveStability(false));
    }

    @Test
    public void testResolveStabilityEnvFalseStaysSnapshot() {
        SanshainMojoDelegate delegate = createDelegate();
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_GA", "false");
        delegate.setEnvironmentVariables(env);
        assertEquals("snapshot", delegate.resolveStability(false));
    }

    @Test
    public void testResolveStabilityPropertyWinsOverEnvFalse() {
        SanshainMojoDelegate delegate = createDelegate();
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_GA", "false");
        delegate.setEnvironmentVariables(env);
        assertEquals("ga", delegate.resolveStability(true));
    }

    // --- Config validation happens before any network call ---

    @Test
    public void testBranchEraConfigFailsBeforeAnyRequest() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n" +
                "  branch: feature-x\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  version: 1.0.0");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("'branch' is no longer supported"), ex.getMessage());
        wireMock.verify(0, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testExecuteNoServiceNameWarnsAndSkips() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "serviceName", null);

        // Default (non-strict) mode: should warn and skip, not throw
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testExecuteOpenApiFileNotFoundThrows() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "provide:\n" +
                "  openApiFile: nonexistent.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");

        assertThrows(Exception.class, mojo::execute);
    }

    @Test
    public void testServiceNameFromConfig() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: config-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "serviceName", null);
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.producername", equalTo("config-service"))));
    }

    @Test
    public void testSanshainUrlFromConfig() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "sanshainUrl", null);
        mojo.execute();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testDefaultSanshainUrl() throws Exception {
        // When no URL is configured anywhere, defaults to http://localhost:8080
        String yaml = "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "sanshainUrl", null);

        // This will fail to connect to localhost:8080, but we can verify the error message
        Exception ex = assertThrows(Exception.class, mojo::execute);
        assertTrue(ex.getMessage().contains("localhost:8080") || ex.getMessage().contains("Failed to connect"));
    }

    @Test
    public void testCompressionDefault() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "compression", null);
        mojo.execute();

        // Default compression is true
        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Content-Encoding", equalTo("gzip")));
    }

    @Test
    public void testCompressionDisabledInConfig() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "compression: false\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "compression", null);
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Content-Encoding"));
    }

    @Test
    public void testTokenFromMavenProperty() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "token", "my-token");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Authorization", equalTo("Bearer my-token")));
    }

    @Test
    public void testSanshainUrlFromSettings() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "sanshainUrl", null);

        // Create settings with server configuration containing sanshainUrl
        Settings settings = new Settings();
        Server server = new Server();
        server.setId("sanshain");
        server.setPassword("my-token");
        org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        org.codehaus.plexus.util.xml.Xpp3Dom urlNode = new org.codehaus.plexus.util.xml.Xpp3Dom("sanshainUrl");
        urlNode.setValue(baseUrl);
        config.addChild(urlNode);
        server.setConfiguration(config);
        settings.addServer(server);
        setField(mojo, "settings", settings);

        mojo.execute();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testMavenPropertyUrlOverridesSettings() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        // sanshainUrl is already set to baseUrl by createMojo

        // Create settings pointing to a different URL (should be ignored)
        Settings settings = new Settings();
        Server server = new Server();
        server.setId("sanshain");
        org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        org.codehaus.plexus.util.xml.Xpp3Dom urlNode = new org.codehaus.plexus.util.xml.Xpp3Dom("sanshainUrl");
        urlNode.setValue("http://should-not-be-used:9999");
        config.addChild(urlNode);
        server.setConfiguration(config);
        settings.addServer(server);
        setField(mojo, "settings", settings);

        mojo.execute();

        // Should use the maven property URL (baseUrl), not the settings URL
        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testSettingsUrlOverridesYamlUrl() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        // YAML has a different URL that would fail
        String yaml = "sanshainUrl: http://yaml-url-should-not-be-used:9999\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "sanshainUrl", null);

        // Settings URL should win over YAML
        Settings settings = new Settings();
        Server server = new Server();
        server.setId("sanshain");
        org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
        org.codehaus.plexus.util.xml.Xpp3Dom urlNode = new org.codehaus.plexus.util.xml.Xpp3Dom("sanshainUrl");
        urlNode.setValue(baseUrl);
        config.addChild(urlNode);
        server.setConfiguration(config);
        settings.addServer(server);
        setField(mojo, "settings", settings);

        mojo.execute();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testNoProvideConfigWarnsAndSkipsByDefault() throws Exception {
        // No provide config, no serviceName — should warn and skip (not fail)
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "serviceName", null);
        // openApiFile points to a file that exists but no serviceName → should skip
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testNoProvideConfigFailsWhenStrict() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "serviceName", null);
        setField(mojo, "strict", true);
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    public void testNoSpecFilesWarnsAndSkipsByDefault() throws Exception {
        // serviceName set but no spec files configured and default openapi.yaml doesn't exist
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        // Remove the default openapi file so nothing is found
        setField(mojo, "openApiFile", new File(tempDir.toFile(), "nonexistent.yaml"));
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testNoSpecFilesFailsWhenStrict() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "openApiFile", new File(tempDir.toFile(), "nonexistent.yaml"));
        setField(mojo, "strict", true);
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    public void testServerErrorPropagates() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(400).withBody("Bad request")));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");

        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    /** Provides are never cached: the service treats an identical re-provide as a no-op. */
    @Test
    public void testTwoConsecutiveProvidesBothUpload() throws Exception {
        String yaml = "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";
        String spec = "openapi: 3.0.0\ninfo:\n  title: Test\n  version: 1.0.0";
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\",\"stability\":\"snapshot\",\"content_hash\":\"sha256:x\","
                                + "\"changes\":{\"inserts\":1,\"updates\":0,\"deletes\":0}}")));

        createMojo(yaml, spec).execute();
        createMojo(yaml, spec).execute();

        wireMock.verify(2, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testDryRunProvideDoesNotBlockTheFollowingPublish() throws Exception {
        String yaml = "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";
        String spec = "openapi: 3.0.0\ninfo:\n  title: Test\n  version: 1.0.0";
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\",\"stability\":\"snapshot\",\"content_hash\":\"sha256:x\"}")));

        ProvideMojo dry = createMojo(yaml, spec);
        setField(dry, "dryRun", true);
        dry.execute();
        createMojo(yaml, spec).execute();

        wireMock.verify(2, postRequestedFor(urlEqualTo("/provide")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.dry_run", equalTo("false"))));
    }

    @Test
    public void testOldCacheFileWithProvidesDoesNotSkip() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(target.resolve(".sanshain-cache.json"),
                "{\"provides\":{\"openapi.yaml\":{\"content_hash\":\"sha256:anything\"}},\"requires\":{}}");
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)));

        createMojo("serviceName: my-service\nprovide:\n  openApiFile: openapi.yaml\n",
                "openapi: 3.0.0\ninfo:\n  version: 1.0.0").execute();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testResolveBestEffortDefaultIsFalse() {
        SanshainMojoDelegate delegate = createDelegate();
        assertFalse(delegate.resolveBestEffort(null, new SanshainConfig()));
    }

    @Test
    public void testResolveBestEffortMavenPropertyTrue() {
        SanshainMojoDelegate delegate = createDelegate();
        assertTrue(delegate.resolveBestEffort(true, new SanshainConfig()));
    }

    @Test
    public void testResolveBestEffortFromConfig() {
        SanshainMojoDelegate delegate = createDelegate();
        SanshainConfig config = new SanshainConfig();
        config.setBestEffort(true);
        assertTrue(delegate.resolveBestEffort(null, config));
    }

    @Test
    public void testResolveBestEffortFromEnvVariable() {
        SanshainMojoDelegate delegate = createDelegate();
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_BEST_EFFORT", "true");
        delegate.setEnvironmentVariables(env);
        assertTrue(delegate.resolveBestEffort(null, new SanshainConfig()));
    }

    @Test
    public void testBestEffortSuppressesInvalidYamlError() throws Exception {
        // Create an invalid YAML file
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, "invalid: yaml: : content");

        ProvideMojo mojo = new ProvideMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", configPath.toFile());
        setField(mojo, "bestEffort", true);
        setField(mojo, "settings", null);
        setField(mojo, "serverId", "sanshain");

        // Should not throw even with invalid YAML because bestEffort=true (Maven property)
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testBestEffortSuppressesIoException() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: my-dir\n";

        Files.createDirectory(tempDir.resolve("my-dir"));

        ProvideMojo mojo = createMojo(yaml, "");
        setField(mojo, "openApiFile", tempDir.resolve("my-dir").toFile());
        setField(mojo, "bestEffort", true);

        // Reading a directory as a file should throw IOException, but bestEffort suppresses it
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testStrictAndBestEffortAbort() throws Exception {
        ProvideMojo mojo = new ProvideMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", tempDir.resolve("non-existent.yaml").toFile());
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);
        setField(mojo, "serviceName", null); // This should trigger abort()
        setField(mojo, "settings", null);
        setField(mojo, "serverId", "sanshain");

        // With strict=true and bestEffort=true, it should NOT throw
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testStrictAbortFails() throws Exception {
        ProvideMojo mojo = new ProvideMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", tempDir.resolve("non-existent.yaml").toFile());
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", false);
        setField(mojo, "serviceName", null); // This should trigger abort()
        setField(mojo, "settings", null);
        setField(mojo, "serverId", "sanshain");

        // With strict=true and bestEffort=false, it SHOULD throw
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    public void testBestEffortDoesNotFailOnServerError() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "bestEffort", true);

        // Should not throw even though server returned 500
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testProvideMojoCombineIntegration() throws Exception {
        // Prepare main and sub YAML files in tempDir
        String mainYaml = "openapi: 3.0.0\n" +
                "info:\n" +
                "  version: 1.0.0\n" +
                "paths:\n" +
                "  /test:\n" +
                "    get:\n" +
                "      responses:\n" +
                "        '200':\n" +
                "          schema:\n" +
                "            $ref: './dto.yaml'\n";
        String dtoYaml = "type: object\n" +
                "properties:\n" +
                "  name:\n" +
                "    type: string\n";

        Path mainPath = tempDir.resolve("openapi.yaml");
        Path dtoPath = tempDir.resolve("dto.yaml");

        Files.writeString(mainPath, mainYaml);
        Files.writeString(dtoPath, dtoYaml);

        // Stub the WireMock server
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202).withBody("{\"version\": \"1.0.0\", \"stability\": \"snapshot\", \"content_hash\": \"somehash\"}")));

        // Create mojo and activate combine
        String yamlConfig = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "combine: true\n" +
                "provide:\n" +
                "  file: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yamlConfig, mainYaml);
        setField(mojo, "combine", true);

        // Execute
        mojo.execute();

        // Verify wireMock received combined spec (containing "name" and "string" instead of "$ref")
        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(containing("name"))
                .withRequestBody(containing("string"))
                .withRequestBody(notContaining("$ref")));
    }

    @Test
    public void testSpecFileResolvesNextToConfigInAnotherFolder() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        Path confDir = Files.createDirectories(tempDir.resolve("conf"));
        Files.writeString(confDir.resolve("api.yaml"), "openapi: 3.0.0\ninfo:\n  title: NextToConfig\n  version: 1.0.0");
        Files.writeString(tempDir.resolve("api.yaml"), "openapi: 3.0.0\ninfo:\n  title: InBaseDir\n  version: 1.0.0");

        ProvideMojo mojo = createMojo("serviceName: my-service\n" +
                "provide:\n" +
                "  file: api.yaml\n", "openapi: 3.0.0");
        Files.writeString(confDir.resolve("sanshain.yaml"), "serviceName: my-service\n" +
                "provide:\n" +
                "  file: api.yaml\n");
        setField(mojo, "configFile", confDir.resolve("sanshain.yaml").toFile());
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("NextToConfig"))));
    }

    @Test
    public void testAbsoluteSpecPathStaysAbsolute() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
        Path spec = elsewhere.resolve("abs.yaml");
        Files.writeString(spec, "openapi: 3.0.0\ninfo:\n  title: Absolute\n  version: 1.0.0");

        ProvideMojo mojo = createMojo("serviceName: my-service\n" +
                "provides:\n" +
                "  - file: " + spec.toAbsolutePath() + "\n", "openapi: 3.0.0");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("Absolute"))));
    }

    @Test
    public void testUnknownFieldIsIgnoredAndProvideContinues() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));
        ProvideMojo mojo = createMojo("serviceName: my-service\ncatalogInf: c.yaml\n" +
                "provide:\n  openApiFile: openapi.yaml\n", "openapi: 3.0.0\ninfo:\n  version: 1.0.0");
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        mojo.setLog(log);
        mojo.execute();

        org.mockito.Mockito.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'catalogInf' - ignored");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testUnknownFieldUnderStrictFailsEvenWithBestEffort() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));
        ProvideMojo mojo = createMojo("serviceName: my-service\ncatalogInf: c.yaml\n" +
                "provide:\n  openApiFile: openapi.yaml\n", "openapi: 3.0.0\ninfo:\n  version: 1.0.0");
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);

        org.apache.maven.plugin.MojoFailureException ex = assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
        assertEquals("sanshain.yaml: unknown field 'catalogInf' - rejected (strict)", ex.getMessage());
        wireMock.verify(0, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testLegacyFieldErrorsBeforeAnyCat() throws Exception {
        ProvideMojo mojo = createMojo("serviceName: my-service\ncatalogInf: c.yaml\nreleaseBranches: [main]\n", "openapi: 3.0.0");
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        mojo.setLog(log);

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("'releaseBranches' is no longer supported"), ex.getMessage());
        org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).warn(org.mockito.ArgumentMatchers.contains("O_o"));
    }

    @Test
    public void testYamlStrictFailsOnUnknownField() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)));
        ProvideMojo mojo = createMojo("serviceName: my-service\nstrict: true\ncatalogInf: c.yaml\n" +
                "provide:\n  openApiFile: openapi.yaml\n", "openapi: 3.0.0\ninfo:\n  version: 1.0.0");

        org.apache.maven.plugin.MojoFailureException ex = assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
        assertEquals("sanshain.yaml: unknown field 'catalogInf' - rejected (strict)", ex.getMessage());
        wireMock.verify(0, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testStrictPropertyFalseOverridesYamlStrict() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)));
        ProvideMojo mojo = createMojo("serviceName: my-service\nstrict: true\ncatalogInf: c.yaml\n" +
                "provide:\n  openApiFile: openapi.yaml\n", "openapi: 3.0.0\ninfo:\n  version: 1.0.0");
        setField(mojo, "strict", false);
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        mojo.setLog(log);
        mojo.execute();

        org.mockito.Mockito.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'catalogInf' - ignored");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testStrictPropertyTrueOverridesYamlStrictOff() throws Exception {
        ProvideMojo mojo = createMojo("serviceName: my-service\nstrict: false\ncatalogInf: c.yaml\n" +
                "provide:\n  openApiFile: openapi.yaml\n", "openapi: 3.0.0\ninfo:\n  version: 1.0.0");
        setField(mojo, "strict", true);

        org.apache.maven.plugin.MojoFailureException ex = assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
        assertEquals("sanshain.yaml: unknown field 'catalogInf' - rejected (strict)", ex.getMessage());
    }

    @Test
    public void testYamlStrictFailsWhenNothingToProvide() throws Exception {
        ProvideMojo mojo = createMojo("serviceName: my-service\nstrict: true\n", "openapi: 3.0.0");
        setField(mojo, "openApiFile", tempDir.resolve("missing.yaml").toFile());

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("No specification files found"), ex.getMessage());
    }

    private static final String UNKNOWN_TAG =
            "no sanshain-branch 'release-9' - this build was not recorded in a release graph";

    private static final String PROVIDE_YAML = "serviceName: my-service\nprovide:\n  openApiFile: openapi.yaml\n";

    private static final String SPEC = "openapi: 3.0.0\ninfo:\n  title: Test\n  version: 1.0.0";

    @Test
    public void testWarningHeaderIsPrintedAndProvideSucceeds() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)
                .withHeader("Sanshain-Warning", UNKNOWN_TAG)));
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        ProvideMojo mojo = createMojo(PROVIDE_YAML, SPEC);
        mojo.setLog(log);
        setField(mojo, "tag", "release-9");
        mojo.execute();

        org.mockito.Mockito.verify(log).warn(UNKNOWN_TAG);
        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.tag", equalTo("release-9"))));
    }

    @Test
    public void testSeveralWarningHeadersArePrintedOnePerLine() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)
                .withHeader("Sanshain-Warning", "trunk and tag are mutually exclusive - this build was recorded in neither",
                        "a, b")));
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        ProvideMojo mojo = createMojo(PROVIDE_YAML, SPEC);
        mojo.setLog(log);
        mojo.execute();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(log);
        order.verify(log).warn("trunk and tag are mutually exclusive - this build was recorded in neither");
        order.verify(log).warn("a, b");
    }

    @Test
    public void testWarningUnderStrictFailsAfterTheCall() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)
                .withHeader("Sanshain-Warning", UNKNOWN_TAG)));
        ProvideMojo mojo = createMojo(PROVIDE_YAML, SPEC);
        mojo.setLog(org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class));
        setField(mojo, "strict", true);
        org.apache.maven.plugin.MojoFailureException ex =
                assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
        assertEquals("strict: Sanshain warned: " + UNKNOWN_TAG, ex.getMessage());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testWarningUnderStrictFailsEvenWithBestEffort() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)
                .withHeader("Sanshain-Warning", UNKNOWN_TAG)));
        ProvideMojo mojo = createMojo(PROVIDE_YAML, SPEC);
        mojo.setLog(org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class));
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);
        org.apache.maven.plugin.MojoFailureException ex =
                assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
        assertEquals("strict: Sanshain warned: " + UNKNOWN_TAG, ex.getMessage());
    }

    @Test
    public void testRetireWarningIsPrintedAndFailsUnderStrict() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)
                .withHeader("Sanshain-Warning", "retire noted").withBody("{}")));
        String yaml = "serviceName: my-service\nprovides:\n  - apiType: openapi\n    retired: true\n";

        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        ProvideMojo plain = createMojo(yaml, SPEC);
        plain.setLog(log);
        plain.execute();
        org.mockito.Mockito.verify(log).warn("retire noted");

        ProvideMojo strictMojo = createMojo(yaml, SPEC);
        strictMojo.setLog(org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class));
        setField(strictMojo, "strict", true);
        org.apache.maven.plugin.MojoFailureException ex =
                assertThrows(org.apache.maven.plugin.MojoFailureException.class, strictMojo::execute);
        assertEquals("strict: Sanshain warned: retire noted", ex.getMessage());
    }

    @Test
    public void testLocalTrunkAndTagWarnsAndSendsNeither() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide")).willReturn(aResponse().withStatus(202)));
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        ProvideMojo mojo = createMojo(PROVIDE_YAML, SPEC);
        mojo.setLog(log);
        setField(mojo, "trunk", true);
        setField(mojo, "tag", "release-9");
        mojo.execute();

        org.mockito.Mockito.verify(log).warn("trunk and tag are mutually exclusive - this build is recorded in neither");
        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.trunk", absent()))
                .withRequestBody(matchingJsonPath("$.tag", absent())));
    }

    @Test
    public void testLocalTrunkAndTagUnderStrictFailsBeforeAnyRequest() throws Exception {
        ProvideMojo mojo = createMojo(PROVIDE_YAML, SPEC);
        mojo.setLog(org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class));
        setField(mojo, "trunk", true);
        setField(mojo, "tag", "release-9");
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);
        org.apache.maven.plugin.MojoFailureException ex =
                assertThrows(org.apache.maven.plugin.MojoFailureException.class, mojo::execute);
        assertEquals("strict: trunk and tag are mutually exclusive - this build is recorded in neither", ex.getMessage());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }
}
