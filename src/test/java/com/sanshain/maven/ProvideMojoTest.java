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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private SanshainMojoDelegate createDelegate(File baseDir) {
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.monitor.logging.DefaultLog(new org.codehaus.plexus.logging.console.ConsoleLogger());
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, baseDir, null, false);
        delegate.setEnvironmentVariables(Collections.emptyMap());
        return delegate;
    }

    @Test
    public void testGetGitBranch() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(new File("."));

        Method getJGitBranchMethod = findMethod(SanshainMojoDelegate.class, "getJGitBranch");
        getJGitBranchMethod.setAccessible(true);

        // getJGitBranch only returns a branch when JGit finds a real (non-detached) branch.
        // In CI (detached HEAD / shallow clone), it returns null — that's expected.
        String branch = (String) getJGitBranchMethod.invoke(delegate);
        if (branch != null) {
            assertFalse(branch.isEmpty(), "Branch name should not be empty");
        }
    }

    @Test
    public void testGetGitBranchNoRepo(@TempDir Path emptyDir) throws Exception {
        SanshainMojoDelegate delegate = createDelegate(emptyDir.toFile());

        Method getJGitBranchMethod = findMethod(SanshainMojoDelegate.class, "getJGitBranch");
        getJGitBranchMethod.setAccessible(true);

        // No git repo -> JGit cannot find a branch
        String branch = (String) getJGitBranchMethod.invoke(delegate);
        assertNull(branch);
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
                .withRequestBody(matchingJsonPath("$.producername", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: \"3.0.0\""))));
    }

    // --- sourceProtectedBranch: Mojo-level wiring (explicit, auto-detected, and cost) ---

    @Test
    public void testExplicitSourceProtectedBranchReachesTheWire() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        setField(mojo, "sourceProtectedBranch", "release/1.2");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.source_protected_branch", equalTo("release/1.2"))));
    }

    @Test
    public void testExplicitSourceProtectedBranchSkipsTheProtectedBranchesFetch() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        setField(mojo, "sourceProtectedBranch", "release/1.2");
        mojo.execute();

        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/branches/protected")));
    }

    @Test
    public void testAutoDetectedSourceProtectedBranchReachesTheWire() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/branches/protected"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"master\"]")));
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        // A real fork point: master, then a feature branch one commit ahead.
        try (Git git = initRepoWithCommit(tempDir.toFile(), "initial")) {
            git.checkout().setName("feature-x").setCreateBranch(true).call();
            commit(git, tempDir.toFile(), "feature work");
        }

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.source_protected_branch", equalTo("master"))));
    }

    /**
     * Detection costs an HTTP request, so it must not run on a build that uploads nothing. Guards the
     * zero-request path that client-side content caching exists to provide.
     */
    @Test
    public void testUnchangedSpecMakesNoRequestsAtAll() throws Exception {
        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";
        String spec = "openapi: 3.0.0\ninfo:\n  title: Test";

        ProvideMojo first = createMojo(yaml, spec);

        // The server hashes the bytes it receives with the same algorithm and format the client uses,
        // so echo back the hash of what will actually be uploaded — anything else never re-matches.
        String uploadedHash = SanshainCache.computeHash(
                new SpecCombiner().combine(tempDir.resolve("openapi.yaml").toFile(), "openapi"));

        wireMock.stubFor(get(urlPathEqualTo("/branches/protected"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[\"master\"]")));
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"" + uploadedHash
                                + "\",\"changes\":{\"inserts\":1,\"updates\":0,\"deletes\":0}}")));

        // First run uploads and primes target/.sanshain-cache.json.
        first.execute();
        // Second run sees an identical spec and must short-circuit before any HTTP call.
        createMojo(yaml, spec).execute();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/provide")));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/branches/protected")));
    }

    @Test
    public void testAuthorPassedToProvidePayload() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        setField(mojo, "author", "jane@example.com");
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.author", equalTo("jane@example.com"))));
    }

    @Test
    public void testAuthorAbsentWhenNotSet() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0\ninfo:\n  title: Test");
        mojo.execute();

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertTrue(body.contains("\"author\":null"), "author should be null when not set: " + body);
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
    @Test
    public void testResolveBranchExplicitValue() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());

        // Explicit branch value should always be returned as-is
        assertEquals("my-feature", delegate.resolveBranch("my-feature"));
    }

    @Test
    public void testResolveBranchFromCIEnvironment() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());

        // Set GITHUB_REF_NAME as a CI variable
        Map<String, String> ciEnv = new HashMap<>();
        ciEnv.put("GITHUB_REF_NAME", "ci-branch-42");
        delegate.setEnvironmentVariables(ciEnv);

        assertEquals("ci-branch-42", delegate.resolveBranch(null));
    }

    @Test
    public void testResolveBranchSanshainBranchEnvWins() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());

        Map<String, String> ciEnv = new HashMap<>();
        ciEnv.put("SANSHAIN_BRANCH", "sanshain-wins");
        ciEnv.put("GITHUB_REF_NAME", "github-loses");
        delegate.setEnvironmentVariables(ciEnv);

        assertEquals("sanshain-wins", delegate.resolveBranch(null));
    }

    @Test
    public void testResolveBranchNoCIFallsToJGit() throws Exception {
        // tempDir has a git repo (initialized in setUp)
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());

        // No CI env vars set (empty map from createDelegate)
        String branch = delegate.resolveBranch(null);
        // JGit should find the branch from the repo initialized in setUp
        assertNotNull(branch, "JGit should detect branch from test repo");
    }

    @Test
    public void testResolveBranchNoRepoNoCIReturnsNull(@TempDir Path emptyDir) throws Exception {
        SanshainMojoDelegate delegate = createDelegate(emptyDir.toFile());

        // No CI env, no git repo -> null
        String branch = delegate.resolveBranch(null);
        assertNull(branch, "Should return null when no branch source is available");
    }

    @Test
    public void testResolveForceDefaultIsFalse() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertFalse(delegate.resolveForce(false));
    }

    @Test
    public void testResolveForceMavenPropertyTrue() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertTrue(delegate.resolveForce(true));
    }

    @Test
    public void testResolveForceFromEnvVariable() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_FORCE", "true");
        delegate.setEnvironmentVariables(env);
        assertTrue(delegate.resolveForce(false));
    }

    @Test
    public void testResolveForceEnvFalse() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_FORCE", "false");
        delegate.setEnvironmentVariables(env);
        assertFalse(delegate.resolveForce(false));
    }

    @Test
    public void testResolveForceMavenPropertyWinsOverEnv() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_FORCE", "false");
        delegate.setEnvironmentVariables(env);
        assertTrue(delegate.resolveForce(true));
    }

    @Test
    public void testResolvePullFromBranchDefaultIsNull() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertNull(delegate.resolvePullFromBranch(null));
    }

    @Test
    public void testResolvePullFromBranchMavenProperty() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertEquals("release/1.0", delegate.resolvePullFromBranch("release/1.0"));
    }

    @Test
    public void testResolvePullFromBranchFromEnvVariable() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_PULL_FROM_BRANCH", "release/2.0");
        delegate.setEnvironmentVariables(env);
        assertEquals("release/2.0", delegate.resolvePullFromBranch(null));
    }

    @Test
    public void testResolvePullFromBranchMavenPropertyWinsOverEnv() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_PULL_FROM_BRANCH", "release/2.0");
        delegate.setEnvironmentVariables(env);
        assertEquals("release/1.0", delegate.resolvePullFromBranch("release/1.0"));
    }

    @Test
    public void testResolveSourceProtectedBranchDefaultIsNull() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertNull(delegate.resolveSourceProtectedBranch(null));
    }

    @Test
    public void testResolveSourceProtectedBranchMavenProperty() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertEquals("release/1.2", delegate.resolveSourceProtectedBranch("release/1.2"));
    }

    @Test
    public void testResolveSourceProtectedBranchFromEnvVariable() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_SOURCE_PROTECTED_BRANCH", "release/2.0");
        delegate.setEnvironmentVariables(env);
        assertEquals("release/2.0", delegate.resolveSourceProtectedBranch(null));
    }

    @Test
    public void testResolveSourceProtectedBranchMavenPropertyWinsOverEnv() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_SOURCE_PROTECTED_BRANCH", "release/2.0");
        delegate.setEnvironmentVariables(env);
        assertEquals("release/1.2", delegate.resolveSourceProtectedBranch("release/1.2"));
    }

    // --- detectSourceProtectedBranch: git merge-base auto-detection ---

    /**
     * The initial branch name (JGit respects the host's init.defaultBranch, so it isn't
     * reliably "master") is renamed to "master" so tests have a deterministic name to assert on.
     */
    private Git initRepoWithCommit(File dir, String message) throws Exception {
        Git git = Git.init().setDirectory(dir).call();
        Files.writeString(dir.toPath().resolve("f-" + System.nanoTime() + ".txt"), message);
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call();
        if (!"master".equals(git.getRepository().getBranch())) {
            git.branchRename().setNewName("master").call();
        }
        return git;
    }

    private void commit(Git git, File dir, String message) throws Exception {
        Files.writeString(dir.toPath().resolve("f-" + System.nanoTime() + ".txt"), message);
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call();
    }

    @Test
    public void testDetectSourceProtectedBranchNoPatternsReturnsNull(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            assertNull(delegate.detectSourceProtectedBranch(Collections.emptyList()));
        }
    }

    @Test
    public void testDetectSourceProtectedBranchNoMatchingBranchReturnsNull(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            assertNull(delegate.detectSourceProtectedBranch(List.of("release/*")));
        }
    }

    @Test
    public void testDetectSourceProtectedBranchPicksDirectAncestor(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            git.branchCreate().setName("feature-x").call();
            git.checkout().setName("feature-x").call();
            commit(git, repoDir.toFile(), "feature work");

            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            assertEquals("master", delegate.detectSourceProtectedBranch(List.of("master")));
        }
    }

    @Test
    public void testDetectSourceProtectedBranchWildcardMatches(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            git.branchCreate().setName("release/1.2").call();
            git.checkout().setName("feature-x").setCreateBranch(true).call();
            commit(git, repoDir.toFile(), "feature work");

            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            assertEquals("release/1.2", delegate.detectSourceProtectedBranch(List.of("release/*")));
        }
    }

    @Test
    public void testDetectSourceProtectedBranchCIHintWinsWhenValidCandidate(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            git.branchCreate().setName("release/1.2").call();
            git.checkout().setName("feature-x").setCreateBranch(true).call();
            commit(git, repoDir.toFile(), "feature work");

            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            Map<String, String> env = new HashMap<>();
            env.put("GITHUB_BASE_REF", "master");
            delegate.setEnvironmentVariables(env);

            // Both "master" and "release/1.2" are valid direct-ancestor candidates;
            // the CI hint must win over merge-base tie-breaking.
            assertEquals("master", delegate.detectSourceProtectedBranch(List.of("master", "release/*")));
        }
    }

    @Test
    public void testDetectSourceProtectedBranchIgnoresCIHintNotInCandidates(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            git.branchCreate().setName("feature-x").call();
            git.checkout().setName("feature-x").call();
            commit(git, repoDir.toFile(), "feature work");

            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            Map<String, String> env = new HashMap<>();
            env.put("GITHUB_BASE_REF", "not-a-protected-branch");
            delegate.setEnvironmentVariables(env);

            assertEquals("master", delegate.detectSourceProtectedBranch(List.of("master")));
        }
    }

    @Test
    public void testDetectSourceProtectedBranchTieBreaksAlphabetically(@TempDir Path repoDir) throws Exception {
        try (Git git = initRepoWithCommit(repoDir.toFile(), "initial")) {
            // "main" and "master" both point at the exact same commit as HEAD's ancestor.
            git.branchCreate().setName("main").call();
            git.checkout().setName("feature-x").setCreateBranch(true).call();
            commit(git, repoDir.toFile(), "feature work");

            SanshainMojoDelegate delegate = createDelegate(repoDir.toFile());
            assertEquals("main", delegate.detectSourceProtectedBranch(List.of("main", "master")));
        }
    }

    @Test
    public void testForceModeSendsForceInPayload() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        String yaml = "sanshainUrl: " + baseUrl + "\n" +
                "serviceName: my-service\n" +
                "provide:\n" +
                "  openApiFile: openapi.yaml\n";

        ProvideMojo mojo = createMojo(yaml, "openapi: 3.0.0");
        setField(mojo, "force", true);
        mojo.execute();

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.force", equalTo("true"))));
    }

    @Test
    public void testResolveBestEffortDefaultIsFalse() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertFalse(delegate.resolveBestEffort(null, new SanshainConfig()));
    }

    @Test
    public void testResolveBestEffortMavenPropertyTrue() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        assertTrue(delegate.resolveBestEffort(true, new SanshainConfig()));
    }

    @Test
    public void testResolveBestEffortFromConfig() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
        SanshainConfig config = new SanshainConfig();
        config.setBestEffort(true);
        assertTrue(delegate.resolveBestEffort(null, config));
    }

    @Test
    public void testResolveBestEffortFromEnvVariable() throws Exception {
        SanshainMojoDelegate delegate = createDelegate(tempDir.toFile());
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
                .willReturn(aResponse().withStatus(202).withBody("{\"version\": 1, \"content_hash\": \"somehash\"}")));

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
