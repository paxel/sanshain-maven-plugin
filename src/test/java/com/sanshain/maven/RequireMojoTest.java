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

import org.eclipse.jgit.api.Git;

import java.io.IOException;
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
    public void setUp() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        Git.init().setDirectory(tempDir.toFile()).call().close();
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
        setField(mojo, "timeout", null);
        setField(mojo, "compression", false);
        setField(mojo, "serverId", "sanshain");
        setField(mojo, "settings", null);
        return mojo;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void testSingleEndpointUsesGetRequire() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("openapi: 3.0.0")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("servicename", equalTo("user-service"))
                .withQueryParam("method", equalTo("GET"))
                .withQueryParam("path", equalTo("/api/v1/users")));

        Path outputFile = tempDir.resolve("output/user-service_api_v1_users_GET.yaml");
        assertTrue(Files.exists(outputFile));
        assertEquals("openapi: 3.0.0", Files.readString(outputFile));
    }

    @Test
    public void testMultipleEndpointsUsesRequireBundle() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
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
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("user-service")))
                .withRequestBody(matchingJsonPath("$.endpoints[0].path", equalTo("/api/v1/users")))
                .withRequestBody(matchingJsonPath("$.endpoints[1].path", equalTo("/api/v1/users/{id}"))));

        // No individual /require calls should have been made
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/require")));

        Path outputFile = tempDir.resolve("output/user-service_bundle.yaml");
        assertTrue(Files.exists(outputFile));
        assertEquals("merged yaml", Files.readString(outputFile));
    }

    @Test
    public void testNoRequiresThrowsException() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "clientName: test-client\n";

        RequireMojo mojo = createMojo(yaml);

        assertThrows(Exception.class, () -> mojo.execute());
    }

    @Test
    public void testNoClientNameThrowsException() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "requires:\n" +
                "  - serviceName: svc\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api\n";

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "serviceName", null);

        assertThrows(Exception.class, () -> mojo.execute());
    }

    @Test
    public void testEmptyEndpointsSkipsService() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
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
    public void testPerServiceTimeout() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "timeout: 60\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    timeout: 30\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "timeout", null);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("timeout", equalTo("30")));
    }

    @Test
    public void testGlobalTimeoutUsedWhenNoPerServiceTimeout() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "timeout: 45\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "timeout", null);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("timeout", equalTo("45")));
    }

    @Test
    public void testDefaultTimeoutIs120() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "timeout", null);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("timeout", equalTo("120")));
    }

    @Test
    public void testMavenPropertyTimeoutOverridesConfig() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "timeout: 45\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/v1/users\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        setField(mojo, "timeout", 99);
        mojo.execute();

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("timeout", equalTo("99")));
    }

    @Test
    public void testSanshainUrlFromConfig() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: test-client\n" +
                "requires:\n" +
                "  - serviceName: user-service\n" +
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
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: GET\n" +
                "        path: /api/a\n" +
                "  - serviceName: service-b\n" +
                "    outputDirectory: output\n" +
                "    endpoints:\n" +
                "      - method: POST\n" +
                "        path: /api/b\n";

        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        RequireMojo mojo = createMojo(yaml);
        mojo.execute();

        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/require")));

        assertTrue(Files.exists(tempDir.resolve("output/service-a_api_a_GET.yaml")));
        assertTrue(Files.exists(tempDir.resolve("output/service-b_api_b_POST.yaml")));
    }
}
