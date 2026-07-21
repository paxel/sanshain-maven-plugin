package com.sanshain.maven;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class SanshainHttpClientTest {

    private WireMockServer wireMock;
    private SanshainHttpClient client;
    private String baseUrl;

    @BeforeEach
    public void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        Log log = Mockito.mock(Log.class);
        client = new SanshainHttpClient(log);
    }

    @AfterEach
    public void tearDown() {
        wireMock.stop();
    }

    // --- postProvide tests ---

    @Test
    public void testPostProvideSuccess() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"sha256:abc\",\"changes\":{\"inserts\":1,\"updates\":0,\"deletes\":0}}")));

        assertDoesNotThrow(() ->
                client.postProvide(baseUrl, "token123", "my-service", "main",
                        "openapi: 3.0.0", false, false));

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer token123")));
    }

    @Test
    public void testPostProvideNoToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"sha256:abc\",\"changes\":{\"inserts\":0,\"updates\":0,\"deletes\":0}}")));

        client.postProvide(baseUrl, null, "my-service", "main", "openapi: 3.0.0", false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostProvideEmptyToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"sha256:abc\",\"changes\":{\"inserts\":0,\"updates\":0,\"deletes\":0}}")));

        client.postProvide(baseUrl, "", "my-service", "main", "openapi: 3.0.0", false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostProvideWithCompression() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"sha256:abc\",\"changes\":{\"inserts\":0,\"updates\":0,\"deletes\":0}}")));

        client.postProvide(baseUrl, null, "my-service", "main", "openapi: 3.0.0", true, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Content-Encoding", equalTo("gzip")));
    }

    @Test
    public void testPostProvideBadRequest() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(400).withBody("Missing field")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false));
        assertTrue(ex.getMessage().contains("Bad request"));
        assertTrue(ex.getMessage().contains("Missing field"));
    }

    @Test
    public void testPostProvideConflict() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(409).withBody("Protected branch")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false));
        assertTrue(ex.getMessage().contains("Concurrent modification detected"));
    }

    @Test
    public void testPostProvideUnexpectedStatus() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(500).withBody("Server error")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testPostProvideConnectionFailure() {
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide("http://localhost:1", null, "my-service", "main", "yaml", false, false));
        assertTrue(ex.getMessage().contains("Failed to connect"));
    }

    @Test
    public void testPostProvideJsonContainsCorrectFields() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"sha256:abc\",\"changes\":{\"inserts\":0,\"updates\":0,\"deletes\":0}}")));

        client.postProvide(baseUrl, null, "my-service", "feature/test", "openapi: 3.0.0\ninfo:", false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.branch", equalTo("feature/test")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: 3.0.0"))));
    }

    @Test
    public void testPostProvideSpecialCharactersInYaml() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":1,\"content_hash\":\"sha256:abc\",\"changes\":{\"inserts\":0,\"updates\":0,\"deletes\":0}}")));

        String yamlWithSpecialChars = "description: \"quotes \\\"escaped\\\" and tabs\\t\"";
        client.postProvide(baseUrl, null, "my-service", "main", yamlWithSpecialChars, false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.openapi_yaml")));
    }

    // --- getRequire tests ---

    @Test
    public void testGetRequireSuccess() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("openapi: 3.0.0")));

        String result = client.getRequire(baseUrl, "token", "client", "service",
                "main", "/api/users", "GET", 10, false, false, null);

        assertEquals("openapi: 3.0.0", result);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("clientname", equalTo("client"))
                .withQueryParam("servicename", equalTo("service"))
                .withQueryParam("branch", equalTo("main"))
                .withQueryParam("path", equalTo("/api/users"))
                .withQueryParam("method", equalTo("GET"))
                .withQueryParam("timeout", equalTo("10"))
                .withHeader("Authorization", equalTo("Bearer token")));
    }

    @Test
    public void testGetRequireNoToken() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false, null);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testGetRequireWithGzipResponse() throws MojoExecutionException, IOException {
        byte[] compressed = gzipCompress("openapi: 3.0.0".getBytes());
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(compressed)));

        String result = client.getRequire(baseUrl, null, "client", "service",
                "main", "/api", "GET", 10, true, false, null);

        assertEquals("openapi: 3.0.0", result);
    }

    @Test
    public void testGetRequireWithCompression() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, true, false, null);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withHeader("Accept-Encoding", equalTo("gzip")));
    }

    @Test
    public void testGetRequire404() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(404)));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false, null));
        assertTrue(ex.getMessage().contains("Endpoint not found"));
    }

    @Test
    public void testGetRequireUnexpectedStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(500)));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false, null));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testGetRequireUrlEncoding() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequire(baseUrl, null, "my client", "my service",
                "feature/branch", "/api/v1/users/{id}", "GET", 10, false, false, null);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("clientname", equalTo("my client"))
                .withQueryParam("servicename", equalTo("my service"))
                .withQueryParam("branch", equalTo("feature/branch"))
                .withQueryParam("path", equalTo("/api/v1/users/{id}")));
    }

    // --- postRequireBundle tests ---

    @Test
    public void testPostRequireBundleSuccess() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200).withBody("merged openapi yaml")));

        SanshainConfig.EndpointConfig ep1 = new SanshainConfig.EndpointConfig();
        ep1.setMethod("GET");
        ep1.setPath("/api/v1/users");
        SanshainConfig.EndpointConfig ep2 = new SanshainConfig.EndpointConfig();
        ep2.setMethod("GET");
        ep2.setPath("/api/v1/users/{id}");

        String result = client.postRequireBundle(baseUrl, "token", "client", "service",
                "main", List.of(ep1, ep2), 120, false, false, null);

        assertEquals("merged openapi yaml", result);
        wireMock.verify(postRequestedFor(urlEqualTo("/require-bundle"))
                .withHeader("Authorization", equalTo("Bearer token"))
                .withRequestBody(matchingJsonPath("$.clientname", equalTo("client")))
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("service")))
                .withRequestBody(matchingJsonPath("$.branch", equalTo("main")))
                .withRequestBody(matchingJsonPath("$.timeout", equalTo("120")))
                .withRequestBody(matchingJsonPath("$.endpoints[0].path", equalTo("/api/v1/users")))
                .withRequestBody(matchingJsonPath("$.endpoints[0].method", equalTo("GET")))
                .withRequestBody(matchingJsonPath("$.endpoints[1].path", equalTo("/api/v1/users/{id}")))
                .withRequestBody(matchingJsonPath("$.endpoints[1].method", equalTo("GET"))));
    }

    @Test
    public void testPostRequireBundleNoToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        client.postRequireBundle(baseUrl, null, "client", "service", "main",
                List.of(ep), 60, false, false, null);

        wireMock.verify(postRequestedFor(urlEqualTo("/require-bundle"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostRequireBundleWithCompression() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        client.postRequireBundle(baseUrl, null, "client", "service", "main",
                List.of(ep), 60, true, false, null);

        wireMock.verify(postRequestedFor(urlEqualTo("/require-bundle"))
                .withHeader("Content-Encoding", equalTo("gzip"))
                .withHeader("Accept-Encoding", equalTo("gzip")));
    }

    @Test
    public void testPostRequireBundleWithGzipResponse() throws MojoExecutionException, IOException {
        byte[] compressed = gzipCompress("merged yaml".getBytes());
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(compressed)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        String result = client.postRequireBundle(baseUrl, null, "client", "service",
                "main", List.of(ep), 60, true, false, null);

        assertEquals("merged yaml", result);
    }

    @Test
    public void testPostRequireBundle400() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(400).withBody("Empty endpoints")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundle(baseUrl, null, "client", "service", "main",
                        List.of(ep), 60, false, false, null));
        assertTrue(ex.getMessage().contains("Bad request"));
        assertTrue(ex.getMessage().contains("Empty endpoints"));
    }

    @Test
    public void testPostRequireBundle404() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(404)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundle(baseUrl, null, "client", "service", "main",
                        List.of(ep), 60, false, false, null));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    public void testPostRequireBundleUnexpectedStatus() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(500)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundle(baseUrl, null, "client", "service", "main",
                        List.of(ep), 60, false, false, null));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testPostRequireBundleConnectionFailure() {
        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundle("http://localhost:1", null, "client", "service",
                        "main", List.of(ep), 60, false, false, null));
        assertTrue(ex.getMessage().contains("Failed to connect"));
    }

    @Test
    public void testRedirectSupport() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse()
                        .withStatus(308)
                        .withHeader("Location", baseUrl + "/new-require")));
        wireMock.stubFor(get(urlPathEqualTo("/new-require"))
                .willReturn(aResponse().withStatus(200).withBody("redirected yaml")));

        String result = client.getRequire(baseUrl, null, "client", "service",
                "main", "/api", "GET", 10, false, false, null);

        assertEquals("redirected yaml", result);
    }

    @Test
    public void testInsecureSsl() throws MojoExecutionException {
        WireMockServer httpsMock = new WireMockServer(WireMockConfiguration.wireMockConfig()
                .dynamicHttpsPort());
        httpsMock.start();
        try {
            String httpsBaseUrl = "https://localhost:" + httpsMock.httpsPort();
            httpsMock.stubFor(get(urlPathEqualTo("/require"))
                    .willReturn(aResponse().withStatus(200).withBody("secure yaml")));

            SanshainHttpClient insecureClient = new SanshainHttpClient(Mockito.mock(Log.class), true);
            String result = insecureClient.getRequire(httpsBaseUrl, null, "client", "service",
                    "main", "/api", "GET", 10, false, false, null);

            assertEquals("secure yaml", result);
        } finally {
            httpsMock.stop();
        }
    }

    // --- v0.13.0 Feature Tests ---

    @Test
    public void testPostProvideReturnsProvideResponse() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":5,\"content_hash\":\"sha256:abc123\",\"changes\":{\"inserts\":2,\"updates\":1,\"deletes\":0}}")));

        ProvideResponse resp = client.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false);
        assertNotNull(resp);
        assertEquals(5, resp.getVersion());
        assertEquals("sha256:abc123", resp.getContentHash());
        assertEquals(2, resp.getChanges().inserts);
        assertEquals(1, resp.getChanges().updates);
        assertEquals(0, resp.getChanges().deletes);
    }

    @Test
    public void testPostProvideWithBaseVersion() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":6,\"content_hash\":\"sha256:def\",\"changes\":{\"inserts\":0,\"updates\":1,\"deletes\":0}}")));

        client.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false, 5, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.base_version", equalTo("5"))));
    }

    @Test
    public void testPostProvideConflict409Message() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(409).withBody("version mismatch")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false, 3, false));
        assertTrue(ex.getMessage().contains("Concurrent modification detected"));
        assertTrue(ex.getMessage().contains("Re-run to fetch the latest state"));
    }

    @Test
    public void testGetRequireWithEtag304() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .withHeader("If-None-Match", equalTo("\"sha256:abc\""))
                .willReturn(aResponse().withStatus(304)));

        RequireResult result = client.getRequireWithEtag(baseUrl, null, "client", "service",
                "main", "/api", "GET", 10, false, false, null, "\"sha256:abc\"");

        assertTrue(result.isNotModified());
        assertNull(result.getContent());
    }

    @Test
    public void testGetRequireWithEtagReturnsNewEtag() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"sha256:newHash\"")
                        .withBody("openapi: 3.0.0")));

        RequireResult result = client.getRequireWithEtag(baseUrl, null, "client", "service",
                "main", "/api", "GET", 10, false, false, null, null);

        assertFalse(result.isNotModified());
        assertEquals("openapi: 3.0.0", result.getContent());
        assertEquals("\"sha256:newHash\"", result.getEtag());
    }

    @Test
    public void testPostRequireBundleWithEtag304() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .withHeader("If-None-Match", equalTo("\"sha256:bundleHash\""))
                .willReturn(aResponse().withStatus(304)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        RequireResult result = client.postRequireBundleWithEtag(baseUrl, null, "client", "service",
                "main", List.of(ep), 60, false, false, null, "\"sha256:bundleHash\"");

        assertTrue(result.isNotModified());
    }

    @Test
    public void testPostRequireBundleWithEtagReturnsNewEtag() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("ETag", "\"sha256:newBundleHash\"")
                        .withBody("merged yaml")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        RequireResult result = client.postRequireBundleWithEtag(baseUrl, null, "client", "service",
                "main", List.of(ep), 60, false, false, null, null);

        assertFalse(result.isNotModified());
        assertEquals("merged yaml", result.getContent());
        assertEquals("\"sha256:newBundleHash\"", result.getEtag());
    }

    @Test
    public void testAngryCatOnProvideError() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error")));

        Log log = Mockito.mock(Log.class);
        SanshainHttpClient localClient = new SanshainHttpClient(log);

        assertThrows(MojoExecutionException.class, () ->
                localClient.postProvide(baseUrl, null, "my-service", "main", "yaml", false, false));

        verify(log, atLeastOnce()).error(contains("NOT happy"));
    }

    @Test
    public void testAngryCatOnRequireError() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        Log log = Mockito.mock(Log.class);
        SanshainHttpClient localClient = new SanshainHttpClient(log);

        assertThrows(MojoExecutionException.class, () ->
                localClient.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false, null));

        verify(log, atLeastOnce()).error(contains("NOT happy"));
    }

    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }
}
