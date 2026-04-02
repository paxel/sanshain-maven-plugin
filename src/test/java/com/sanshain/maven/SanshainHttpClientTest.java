package com.sanshain.maven;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPInputStream;
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
                .willReturn(aResponse().withStatus(202)));

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
                .willReturn(aResponse().withStatus(202)));

        client.postProvide(baseUrl, null, "my-service", "main", "openapi: 3.0.0", false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostProvideEmptyToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

        client.postProvide(baseUrl, "", "my-service", "main", "openapi: 3.0.0", false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostProvideWithCompression() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

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
        assertTrue(ex.getMessage().contains("Conflict"));
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
                .willReturn(aResponse().withStatus(202)));

        client.postProvide(baseUrl, null, "my-service", "feature/test", "openapi: 3.0.0\ninfo:", false, false);

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.servicename", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.branch", equalTo("feature/test")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: 3.0.0"))));
    }

    @Test
    public void testPostProvideSpecialCharactersInYaml() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)));

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
                "main", "/api/users", "GET", 10, false, false);

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

        client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false);

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
                "main", "/api", "GET", 10, true, false);

        assertEquals("openapi: 3.0.0", result);
    }

    @Test
    public void testGetRequireWithCompression() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, true, false);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withHeader("Accept-Encoding", equalTo("gzip")));
    }

    @Test
    public void testGetRequire404() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(404)));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false));
        assertTrue(ex.getMessage().contains("Endpoint not found"));
    }

    @Test
    public void testGetRequireUnexpectedStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(500)));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequire(baseUrl, null, "client", "service", "main", "/api", "GET", 10, false, false));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testGetRequireUrlEncoding() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequire(baseUrl, null, "my client", "my service",
                "feature/branch", "/api/v1/users/{id}", "GET", 10, false, false);

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
                "main", List.of(ep1, ep2), 120, false, false);

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
                List.of(ep), 60, false, false);

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
                List.of(ep), 60, true, false);

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
                "main", List.of(ep), 60, true, false);

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
                        List.of(ep), 60, false, false));
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
                        List.of(ep), 60, false, false));
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
                        List.of(ep), 60, false, false));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testPostRequireBundleConnectionFailure() {
        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundle("http://localhost:1", null, "client", "service",
                        "main", List.of(ep), 60, false, false));
        assertTrue(ex.getMessage().contains("Failed to connect"));
    }

    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }
}
