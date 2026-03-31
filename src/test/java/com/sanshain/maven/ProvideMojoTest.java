package com.sanshain.maven;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.eclipse.jgit.api.Git;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class ProvideMojoTest {

    @TempDir
    Path tempDir;

    private WireMockServer wireMock;
    private String baseUrl;

    @BeforeEach
    public void setUp() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        // Initialize a git repo in tempDir so getGitBranch() works
        Git.init().setDirectory(tempDir.toFile()).call().close();
    }

    @AfterEach
    public void tearDown() {
        wireMock.stop();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
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
        setField(mojo, "timeout", null);
        setField(mojo, "compression", false);
        setField(mojo, "serverId", "sanshain");
        setField(mojo, "settings", null);
        return mojo;
    }

    @Test
    public void testGetGitBranch() throws Exception {
        ProvideMojo mojo = new ProvideMojo();
        setField(mojo, "baseDir", new File("."));

        Method getGitBranchMethod = ProvideMojo.class.getDeclaredMethod("getGitBranch");
        getGitBranchMethod.setAccessible(true);

        String branch = (String) getGitBranchMethod.invoke(mojo);
        assertNotNull(branch);
    }

    @Test
    public void testGetGitBranchNoRepo(@TempDir Path emptyDir) throws Exception {
        ProvideMojo mojo = new ProvideMojo();
        setField(mojo, "baseDir", emptyDir.toFile());

        Method getGitBranchMethod = ProvideMojo.class.getDeclaredMethod("getGitBranch");
        getGitBranchMethod.setAccessible(true);

        // No git repo -> should return null (or throw is caught internally)
        String branch = (String) getGitBranchMethod.invoke(mojo);
        assertNull(branch);
    }

    @Test
    public void testExecuteSuccess() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "provide:\n" +
                "  serviceName: my-service\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: 3.0.0"))));
    }

    @Test
    public void testExecuteNoServiceNameThrows() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "serviceName", null);

        assertThrows(MojoExecutionException.class, () -> mojo.execute());
    }

    @Test
    public void testExecuteOpenApiFileNotFoundThrows() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "openApiFile", new File(tempDir.toFile(), "nonexistent.yaml"));

        assertThrows(Exception.class, () -> mojo.execute());
    }

    @Test
    public void testServiceNameFromConfig() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "provide:\n" +
                "  serviceName: config-service\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "serviceName", null);
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("config-service"))));
    }

    @Test
    public void testSanshainUrlFromConfig() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "provide:\n" +
                "  serviceName: my-service\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "sanshainUrl", null);
        mojo.execute();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
    }

    @Test
    public void testDefaultSanshainUrl() throws Exception {
        // When no URL is configured anywhere, defaults to http://localhost:8080
        String yaml = "provide:\n" +
                "  serviceName: my-service\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "sanshainUrl", null);

        // This will fail to connect to localhost:8080, but we can verify the error message
        Exception ex = assertThrows(Exception.class, () -> mojo.execute());
        assertTrue(ex.getMessage().contains("localhost:8080") || ex.getMessage().contains("Failed to connect"));
    }

    @Test
    public void testCompressionDefault() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "provide:\n" +
                "  serviceName: my-service\n" +
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
                "provide:\n" +
                "  serviceName: my-service\n" +
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
                "provide:\n" +
                "  serviceName: my-service\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "token", "my-token");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Authorization", equalTo("Bearer my-token")));
    }

    @Test
    public void testServerErrorPropagates() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(400).withBody("Bad request")));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "provide:\n" +
                "  serviceName: my-service\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");

        assertThrows(MojoExecutionException.class, () -> mojo.execute());
    }
}
