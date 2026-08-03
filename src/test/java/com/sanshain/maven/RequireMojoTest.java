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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class RequireMojoTest {

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

    private RequireMojo createMojo(String yamlContent) throws Exception {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yamlContent);

        RequireMojo mojo = new RequireMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", configPath.toFile());
        setField(mojo, "sanshainUrl", baseUrl);
        setField(mojo, "serviceName", "test-client");
        setField(mojo, "token", null);
        setField(mojo, "compression", false);
        setField(mojo, "serverId", "sanshain");
        setField(mojo, "settings", null);
        return mojo;
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

    @Test
    public void testSingleEndpointUsesGetRequireWithVersion() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.2.0\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("openapi: 3.0.0")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("consumername", equalTo("test-client"))
                .withQueryParam("producername", equalTo("user-service"))
                .withQueryParam("version", equalTo("1.2.0"))
                .withQueryParam("method", equalTo("GET"))
                .withQueryParam("path", equalTo("/api/v1/users")));

        Path outputFile = tempDir.resolve("output/user-service_api_v1_users_GET.yaml");
        assertTrue(Files.exists(outputFile));
        assertEquals("openapi: 3.0.0", Files.readString(outputFile));
    }

    @Test
    public void testRequireSendsNoBranchEraParams() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.2.0\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("openapi: 3.0.0")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("branch", absent())
                .withQueryParam("timeout", absent())
                .withQueryParam("pull_from_branch", absent())
                .withQueryParam("source_protected_branch", absent()));
    }

    @Test
    public void testMultipleEndpointsUsesRequireBundleWithVersion() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 2.0.1\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users/{id}\n";

        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200).withBody("merged yaml")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/require-bundle"))
                .withRequestBody(matchingJsonPath("$.consumername", equalTo("test-client")))
                .withRequestBody(matchingJsonPath("$.producername", equalTo("user-service")))
                .withRequestBody(matchingJsonPath("$.version", equalTo("2.0.1")))
                .withRequestBody(matchingJsonPath("$.endpoints[0].path", equalTo("/api/v1/users")))
                .withRequestBody(matchingJsonPath("$.endpoints[1].path", equalTo("/api/v1/users/{id}"))));

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertFalse(body.contains("\"branch\""), "branch must not be sent in 2.0: " + body);
        assertFalse(body.contains("\"timeout\""), "timeout must not be sent in 2.0: " + body);

        // No individual /require calls should have been made
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/require")));

        Path outputFile = tempDir.resolve("output/user-service_bundle.yaml");
        assertTrue(Files.exists(outputFile));
        assertEquals("merged yaml", Files.readString(outputFile));
    }

    // --- Config validation: parse-time named errors, before any network call ---

    @Test
    public void testMissingVersionIsAHardErrorWithMigrationHint() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        RequireMojo mojo = createMojo(yaml);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(ex.getMessage().contains("requires[0] (user-service): missing 'version'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Sanshain 2.0 pins exact versions"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GET /producers/user-service/versions"), ex.getMessage());
        // Validation happens before any network call
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/require")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/require-bundle")));
    }

    @Test
    public void testBranchEraFieldIsAHardErrorByName() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    branch: feature-x\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        RequireMojo mojo = createMojo(yaml);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(ex.getMessage().contains("'branch' is no longer supported"), ex.getMessage());
        assertTrue(ex.getMessage().contains("replace with an exact 'version' pin"), ex.getMessage());
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/require")));
    }

    @Test
    public void testTimeoutFieldIsAHardErrorByName() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "timeout: 120\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        RequireMojo mojo = createMojo(yaml);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(ex.getMessage().contains("'timeout' is no longer supported"), ex.getMessage());
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/require")));
    }

    @Test
    public void testEmptyEndpointsSkipsService() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    endpoints: []\n";

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        // No HTTP calls should have been made
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/require")));
        wireMock.verify(0, postRequestedFor(urlEqualTo("/require-bundle")));
    }

    @Test
    public void testDefaultOutputDirectory() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml content")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        Path outputFile = tempDir.resolve("target/generated-sources/sanshain/user-service_api_v1_users_GET.yaml");
        assertTrue(Files.exists(outputFile));
    }

    @Test
    public void testSanshainUrlFromConfig() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "sanshainUrl", null);
        mojo.execute();

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/require")));
    }

    @Test
    public void testSanshainUrlFromSettings() throws Exception {
        String yaml = "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("openapi: 3.0.0")));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "sanshainUrl", null);

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

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/require")));
    }

    @Test
    public void testMultipleServicesProcessed() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: service-a\n" +
                "    version: 1.0.0\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/a\n" +
                "  - serviceName: service-b\n" +
                "    version: 3.1.4\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: POST\n" +
                "        path: /api/b\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/require")));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("producername", equalTo("service-b"))
                .withQueryParam("version", equalTo("3.1.4")));

        assertTrue(Files.exists(tempDir.resolve("output/service-a_api_a_GET.yaml")));
        assertTrue(Files.exists(tempDir.resolve("output/service-b_api_b_POST.yaml")));
    }

    @Test
    public void testNoRequiresWarnsAndSkipsByDefault() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n";

        RequireMojo mojo = createMojo(yaml);
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testNoRequiresFailsWhenStrict() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n";

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "strict", true);
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    public void testNoServiceNameWarnsAndSkipsByDefault() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "serviceName", null);
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testNoServiceNameFailsWhenStrict() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "serviceName", null);
        setField(mojo, "strict", true);
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test
    public void testBestEffortSuppressesInvalidYamlError() throws Exception {
        // Create an invalid YAML file
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, "invalid: yaml: : content");

        RequireMojo mojo = new RequireMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", configPath.toFile());
        setField(mojo, "bestEffort", true);
        setField(mojo, "settings", null);
        setField(mojo, "serverId", "sanshain");

        // Should not throw even with invalid YAML because bestEffort=true
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testBestEffortSuppressesIoException() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml content")));

        // Create a directory where the file should be
        Files.createDirectories(tempDir.resolve("output/user-service_api_v1_users_GET.yaml"));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "bestEffort", true);

        // Files.writeString will throw IOException if target is a directory, but bestEffort suppresses it
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    public void testStrictAndBestEffortAbort() throws Exception {
        RequireMojo mojo = new RequireMojo();
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
        RequireMojo mojo = new RequireMojo();
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
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    version: 1.0.0\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "bestEffort", true);

        // Should not throw even though server returned 500
        assertDoesNotThrow(mojo::execute);
    }
}
