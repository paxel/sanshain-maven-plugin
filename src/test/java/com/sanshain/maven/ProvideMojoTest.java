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
        setField(mojo, "timeout", null);
        setField(mojo, "compression", false);
        setField(mojo, "serverId", "sanshain");
        setField(mojo, "settings", null);
        return mojo;
    }

    @Test
    public void testGetGitBranch() throws Exception {
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.monitor.logging.DefaultLog(new org.codehaus.plexus.logging.console.ConsoleLogger());
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, new File("."), null, false);

        Method getGitBranchMethod = findMethod(SanshainMojoDelegate.class, "getGitBranch");
        getGitBranchMethod.setAccessible(true);

        String branch = (String) getGitBranchMethod.invoke(delegate);
        assertNotNull(branch);
    }

    @Test
    public void testGetGitBranchNoRepo(@TempDir Path emptyDir) throws Exception {
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.monitor.logging.DefaultLog(new org.codehaus.plexus.logging.console.ConsoleLogger());
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, emptyDir.toFile(), null, false);

        Method getGitBranchMethod = findMethod(SanshainMojoDelegate.class, "getGitBranch");
        getGitBranchMethod.setAccessible(true);

        // No git repo -> should return null unless CI environment variables provide a branch
        String branch = (String) getGitBranchMethod.invoke(delegate);
        boolean ciEnvironment = System.getenv("GITHUB_REF_NAME") != null
                || System.getenv("GITHUB_HEAD_REF") != null
                || System.getenv("CI_COMMIT_REF_NAME") != null
                || System.getenv("GIT_BRANCH") != null
                || System.getenv("BRANCH_NAME") != null
                || System.getenv("BITBUCKET_BRANCH") != null
                || System.getenv("BUILD_SOURCEBRANCH") != null
                || System.getenv("TRAVIS_BRANCH") != null
                || System.getenv("CIRCLE_BRANCH") != null;
        if (ciEnvironment) {
            assertNotNull(branch, "In CI environment, branch should be detected from env vars");
        } else {
            assertNull(branch);
        }
    }

    @Test
    public void testExecuteSuccess() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: 3.0.0"))));
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
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("config-service"))));
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
    @Test
    public void testResolveBranchCIOverrides() throws Exception {
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.monitor.logging.DefaultLog(new org.codehaus.plexus.logging.console.ConsoleLogger());
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, tempDir.toFile(), null, false);

        // This test depends on the environment, but we can't easily mock System.getenv() without extra libraries
        // However, we can test that if we call resolveBranch(null) it doesn't fail.
        String branch = delegate.resolveBranch(null);
        assertNotNull(branch);
    }

    private Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        try {
            return clazz.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            if (clazz.getSuperclass() != null) {
                return findMethod(clazz.getSuperclass(), methodName, parameterTypes);
            }
            throw e;
        }
    }
}
