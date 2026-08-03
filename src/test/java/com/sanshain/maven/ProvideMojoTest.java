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

    /**
     * A build whose spec is unchanged must stay on the zero-request path that client-side
     * content caching exists to provide.
     */
    @Test
    public void testUnchangedSpecMakesNoRequestsAtAll() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";
        String spec = "openapi: 3.0.0\ninfo:\n  title: Test\n  version: 1.0.0";

        ProvideMojo first = createMojo(yaml, spec);

        // The server hashes the bytes it receives with the same algorithm and format the client uses,
        // so echo back the hash of what will actually be uploaded — anything else never re-matches.
        String uploadedHash = SanshainCache.computeHash(
                new SpecCombiner().combine(tempDir.resolve("openapi.yaml").toFile(), "openapi"));

        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\",\"stability\":\"snapshot\",\"content_hash\":\"" + uploadedHash
                                + "\",\"changes\":{\"inserts\":1,\"updates\":0,\"deletes\":0}}")));

        // First run uploads and primes target/.sanshain-cache.json.
        first.execute();
        // Second run sees an identical spec and must short-circuit before any HTTP call.
        createMojo(yaml, spec).execute();

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
}
