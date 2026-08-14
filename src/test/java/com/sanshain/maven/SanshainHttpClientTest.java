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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class SanshainHttpClientTest {

    private static final String PROVIDE_202_BODY =
            "{\"version\":\"1.0.0\",\"stability\":\"snapshot\",\"content_hash\":\"sha256:abc\","
                    + "\"changes\":{\"inserts\":1,\"updates\":0,\"deletes\":0}}";

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

    private ProvideResponse provide(String apiType) throws MojoExecutionException {
        return client.postProvide(baseUrl, "token123", "my-service", "openapi: 3.0.0",
                "snapshot", false, false, apiType, "openapi.yaml", SanshainStream.none());
    }

    // --- postProvide tests ---

    @Test
    public void testPostProvideSuccess() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        assertDoesNotThrow(() -> provide("openapi"));

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Authorization", equalTo("Bearer token123")));
    }

    @Test
    public void testPostProvideJsonContainsOnly2xFields() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, null, "my-service", "openapi: 3.0.0\ninfo:",
                "ga", false, true, "openapi", "openapi.yaml", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.producername", equalTo("my-service")))
                .withRequestBody(matchingJsonPath("$.stability", equalTo("ga")))
                .withRequestBody(matchingJsonPath("$.dry_run", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.openapi_yaml", containing("openapi: 3.0.0"))));

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertFalse(body.contains("\"branch\""), "no branch in 2.0 payload: " + body);
        assertFalse(body.contains("\"base_version\""), "no base_version in 2.0 payload: " + body);
        assertFalse(body.contains("\"force\""), "no force in 2.0 payload: " + body);
        assertFalse(body.contains("\"api_type\""), "no api_type in 2.0 provide payload: " + body);
        assertFalse(body.contains("\"author\""), "author was removed from the 2.0 contract: " + body);
    }

    @Test
    public void testPostProvideAsyncApiRoutesAndField() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide/asyncapi"))
                .willReturn(aResponse().withStatus(202).withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, null, "my-service", "asyncapi: 2.6.0",
                "snapshot", false, false, "asyncapi", "asyncapi.yaml", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide/asyncapi"))
                .withRequestBody(matchingJsonPath("$.asyncapi_yaml", containing("asyncapi: 2.6.0")))
                .withRequestBody(matchingJsonPath("$.stability", equalTo("snapshot"))));
    }

    @Test
    public void testPostProvideProtoRoutesAndField() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide/grpc"))
                .willReturn(aResponse().withStatus(202).withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, null, "my-service", "syntax = \"proto3\";",
                "snapshot", false, false, "proto", "service.proto", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide/grpc"))
                .withRequestBody(matchingJsonPath("$.proto_content", containing("proto3")))
                .withRequestBody(matchingJsonPath("$.stability", equalTo("snapshot"))));
    }

    @Test
    public void testPostProvideNoToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202).withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, null, "my-service", "openapi: 3.0.0",
                "snapshot", false, false, "openapi", "openapi.yaml", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostProvideEmptyToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202).withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, "", "my-service", "openapi: 3.0.0",
                "snapshot", false, false, "openapi", "openapi.yaml", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testPostProvideWithCompression() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202).withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, null, "my-service", "openapi: 3.0.0",
                "snapshot", true, false, "openapi", "openapi.yaml", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withHeader("Content-Encoding", equalTo("gzip")));
    }

    @Test
    public void testPostProvideReturnsProvideResponse() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.4.0\",\"stability\":\"ga\",\"content_hash\":\"sha256:abc123\","
                                + "\"changes\":{\"inserts\":2,\"updates\":1,\"deletes\":0}}")));

        ProvideResponse resp = provide("openapi");
        assertNotNull(resp);
        assertEquals("1.4.0", resp.getVersion());
        assertEquals("ga", resp.getStability());
        assertEquals("sha256:abc123", resp.getContentHash());
        assertEquals(2, resp.getChanges().inserts);
        assertEquals(1, resp.getChanges().updates);
        assertEquals(0, resp.getChanges().deletes);
        assertTrue(resp.toSummary().contains("1.4.0"));
        assertTrue(resp.toSummary().contains("ga"));
    }

    @Test
    public void testPostProvideBadRequest() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":\"info.version is not strict semver\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(ex.getMessage().contains("Invalid specification (400)"));
        assertTrue(ex.getMessage().contains("info.version is not strict semver"));
    }

    @Test
    public void testPostProvide422RejectedShape() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(422)
                        .withBody("{\"error\":\"unknown field 'branch'\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(ex.getMessage().contains("422"));
        assertTrue(ex.getMessage().contains("unknown field 'branch'"));
    }

    // --- 409 handling: proposed_version surfaced prominently ---

    @Test
    public void testPostProvide409SurfacesProposedVersion() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"GA 1.2.0 is immutable and the content differs\","
                                + "\"proposed_version\":\"1.3.0\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(ex.getMessage().contains("GA 1.2.0 is immutable"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Publish as 1.3.0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("info.version"), ex.getMessage());
        assertTrue(ex.getMessage().contains("openapi.yaml"), ex.getMessage());
    }

    @Test
    public void testPostProvide409ProtoNamesTheVersionMarker() {
        wireMock.stubFor(post(urlEqualTo("/provide/grpc"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"semver lie\",\"proposed_version\":\"2.0.0\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide(baseUrl, null, "my-service", "syntax = \"proto3\";",
                        "ga", false, false, "proto", "service.proto", SanshainStream.none()));
        assertTrue(ex.getMessage().contains("Publish as 2.0.0"), ex.getMessage());
        assertTrue(ex.getMessage().contains("// sanshain-version:"), ex.getMessage());
        assertTrue(ex.getMessage().contains("service.proto"), ex.getMessage());
    }

    @Test
    public void testPostProvide409WithoutProposedVersionStillFails() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(409)
                        .withBody("{\"error\":\"channel message contract owned by another producer\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(ex.getMessage().contains("409"));
        assertTrue(ex.getMessage().contains("channel message contract"));
        assertFalse(ex.getMessage().contains("Publish as"), ex.getMessage());
    }

    @Test
    public void testAngryCatFiresOnVersionRule409() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(409)
                        .withBody("{\"error\":\"GA is immutable\",\"proposed_version\":\"1.3.0\"}")));

        Log log = Mockito.mock(Log.class);
        SanshainHttpClient localClient = new SanshainHttpClient(log);

        assertThrows(MojoExecutionException.class, () ->
                localClient.postProvide(baseUrl, null, "my-service", "yaml",
                        "ga", false, false, "openapi", "openapi.yaml", SanshainStream.none()));

        verify(log, atLeastOnce()).error(contains("HISSSSSSSS!"));
        verify(log, atLeastOnce()).error(contains("Sanshain is NOT happy with this!"));
        verify(log, atLeastOnce()).error(contains("Publish as 1.3.0"));
    }

    @Test
    public void testPostProvideUnexpectedStatus() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(500).withBody("Server error")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testPostProvideConnectionFailure() {
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postProvide("http://localhost:1", null, "my-service", "yaml",
                        "snapshot", false, false, "openapi", "openapi.yaml", SanshainStream.none()));
        assertTrue(ex.getMessage().contains("Failed to connect"));
    }

    // --- Lazy wrong-server diagnosis (GET /version) ---

    @Test
    public void testPre2ServerReplacesConfusingProvideError() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(400).withBody("missing field `branch`")));
        wireMock.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.7.0\",\"instance_id\":\"abc\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertEquals("Sanshain server at " + baseUrl + " is 1.7.0; this client requires Sanshain 2.x — upgrade the server.",
                ex.getMessage());
    }

    @Test
    public void testPre2ServerReplacesConfusingRequireError() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(400).withBody("missing query parameter branch")));
        wireMock.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"version\":\"1.6.2\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequireWithEtag(baseUrl, null, "client", "service",
                        "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("is 1.6.2; this client requires Sanshain 2.x — upgrade the server."),
                ex.getMessage());
    }

    @Test
    public void testNoPreflightAndOnlyOneVersionProbe() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        wireMock.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse().withStatus(200).withBody("{\"version\":\"2.0.0\"}")));

        // Two failing calls on the same client — the /version probe is lazy and memoized.
        assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertThrows(MojoExecutionException.class, () -> provide("openapi"));

        wireMock.verify(1, getRequestedFor(urlEqualTo("/version")));
    }

    @Test
    public void testNoVersionProbeOnSuccess() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202).withBody(PROVIDE_202_BODY)));

        provide("openapi");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/version")));
    }

    @Test
    public void testCurrentServerKeepsOriginalError() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad spec\"}")));
        wireMock.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse().withStatus(200).withBody("{\"version\":\"2.1.0\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(ex.getMessage().contains("bad spec"), ex.getMessage());
        assertFalse(ex.getMessage().contains("upgrade the server"), ex.getMessage());
    }

    // --- getRequire tests ---

    @Test
    public void testGetRequireSuccess() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("openapi: 3.0.0")));

        RequireResult result = client.getRequireWithEtag(baseUrl, "token", "client", "service",
                "1.2.0", "/api/users", "GET", false, false, null, null, SanshainStream.none());

        assertEquals("openapi: 3.0.0", result.getContent());
        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("consumername", equalTo("client"))
                .withQueryParam("producername", equalTo("service"))
                .withQueryParam("version", equalTo("1.2.0"))
                .withQueryParam("path", equalTo("/api/users"))
                .withQueryParam("method", equalTo("GET"))
                .withQueryParam("branch", absent())
                .withQueryParam("timeout", absent())
                .withHeader("Authorization", equalTo("Bearer token")));
    }

    @Test
    public void testGetRequireNoToken() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequireWithEtag(baseUrl, null, "client", "service", "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withoutHeader("Authorization"));
    }

    @Test
    public void testGetRequireRoutesAsyncApiAndProto() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require/asyncapi"))
                .willReturn(aResponse().withStatus(200).withBody("channel yaml")));
        wireMock.stubFor(get(urlPathEqualTo("/require/grpc"))
                .willReturn(aResponse().withStatus(200).withBody("proto snippet")));

        assertEquals("channel yaml", client.getRequireWithEtag(baseUrl, null, "client", "service",
                "1.0.0", "orders.created", "PUB", false, false, "asyncapi", null, SanshainStream.none()).getContent());
        assertEquals("proto snippet", client.getRequireWithEtag(baseUrl, null, "client", "service",
                "1.0.0", "inventory.v1.InventoryService", "GetProduct", false, false, "proto", null, SanshainStream.none()).getContent());
    }

    @Test
    public void testGetRequireWithGzipResponse() throws MojoExecutionException, IOException {
        byte[] compressed = gzipCompress("openapi: 3.0.0".getBytes(StandardCharsets.UTF_8));
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(compressed)));

        RequireResult result = client.getRequireWithEtag(baseUrl, null, "client", "service",
                "1.0.0", "/api", "GET", true, false, null, null, SanshainStream.none());

        assertEquals("openapi: 3.0.0", result.getContent());
    }

    @Test
    public void testGetRequireWithCompression() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequireWithEtag(baseUrl, null, "client", "service", "1.0.0", "/api", "GET", true, false, null, null, SanshainStream.none());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withHeader("Accept-Encoding", equalTo("gzip")));
    }

    @Test
    public void testGetRequire404UnknownVersionNamesThePin() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"unknown version\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequireWithEtag(baseUrl, null, "client", "service", "9.9.9", "/api", "GET", false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("service@9.9.9"), ex.getMessage());
        assertTrue(ex.getMessage().contains("fix the 'version' pin"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GET /producers/service/versions"), ex.getMessage());
    }

    @Test
    public void testGetRequire410DeliberatelyAbsent() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(410).withBody("{\"error\":\"endpoint absent\"}")));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequireWithEtag(baseUrl, null, "client", "service", "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("deliberately"), "message should distinguish 410 from 404: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("Unexpected response"), "410 must not fall into the generic branch: " + ex.getMessage());
    }

    @Test
    public void testGetRequireUnexpectedStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(500)));

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.getRequireWithEtag(baseUrl, null, "client", "service", "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testGetRequireUrlEncoding() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        client.getRequireWithEtag(baseUrl, null, "my client", "my service",
                "1.0.0", "/api/v1/users/{id}", "GET", false, false, null, null, SanshainStream.none());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("consumername", equalTo("my client"))
                .withQueryParam("producername", equalTo("my service"))
                .withQueryParam("path", equalTo("/api/v1/users/{id}")));
    }

    @Test
    public void testGetRequireWithEtag304() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .withHeader("If-None-Match", equalTo("\"sha256:abc\""))
                .willReturn(aResponse().withStatus(304)));

        RequireResult result = client.getRequireWithEtag(baseUrl, null, "client", "service",
                "1.0.0", "/api", "GET", false, false, null, "\"sha256:abc\"", SanshainStream.none());

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
                "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none());

        assertFalse(result.isNotModified());
        assertEquals("openapi: 3.0.0", result.getContent());
        assertEquals("\"sha256:newHash\"", result.getEtag());
    }

    @Test
    public void testGetRequireSurfacesStabilityHeader() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("X-Sanshain-Stability", "snapshot")
                        .withBody("openapi: 3.0.0")));

        RequireResult result = client.getRequireWithEtag(baseUrl, null, "client", "service",
                "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none());

        assertEquals("snapshot", result.getStability());
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

        RequireResult result = client.postRequireBundleWithEtag(baseUrl, "token", "client", "service",
                "1.2.0", List.of(ep1, ep2), false, false, null, null, SanshainStream.none());

        assertEquals("merged openapi yaml", result.getContent());
        wireMock.verify(postRequestedFor(urlEqualTo("/require-bundle"))
                .withHeader("Authorization", equalTo("Bearer token"))
                .withRequestBody(matchingJsonPath("$.consumername", equalTo("client")))
                .withRequestBody(matchingJsonPath("$.producername", equalTo("service")))
                .withRequestBody(matchingJsonPath("$.version", equalTo("1.2.0")))
                .withRequestBody(matchingJsonPath("$.api_type", equalTo("openapi")))
                .withRequestBody(matchingJsonPath("$.endpoints[0].path", equalTo("/api/v1/users")))
                .withRequestBody(matchingJsonPath("$.endpoints[0].method", equalTo("GET")))
                .withRequestBody(matchingJsonPath("$.endpoints[1].path", equalTo("/api/v1/users/{id}")))
                .withRequestBody(matchingJsonPath("$.endpoints[1].method", equalTo("GET"))));

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertFalse(body.contains("\"branch\""), "no branch in 2.0 bundle payload: " + body);
        assertFalse(body.contains("\"timeout\""), "no timeout in 2.0 bundle payload: " + body);
        assertFalse(body.contains("\"pull_from_branch\""), "no pull_from_branch in 2.0 bundle payload: " + body);
        assertFalse(body.contains("\"source_protected_branch\""), "no source_protected_branch in 2.0 bundle payload: " + body);
    }

    @Test
    public void testPostRequireBundleNoToken() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200).withBody("yaml")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0",
                List.of(ep), false, false, null, null, SanshainStream.none());

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

        client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0",
                List.of(ep), true, false, null, null, SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/require-bundle"))
                .withHeader("Content-Encoding", equalTo("gzip"))
                .withHeader("Accept-Encoding", equalTo("gzip")));
    }

    @Test
    public void testPostRequireBundleWithGzipResponse() throws MojoExecutionException, IOException {
        byte[] compressed = gzipCompress("merged yaml".getBytes(StandardCharsets.UTF_8));
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Encoding", "gzip")
                        .withBody(compressed)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        RequireResult result = client.postRequireBundleWithEtag(baseUrl, null, "client", "service",
                "1.0.0", List.of(ep), true, false, null, null, SanshainStream.none());

        assertEquals("merged yaml", result.getContent());
    }

    @Test
    public void testPostRequireBundle400() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(400).withBody("Empty endpoints")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0",
                        List.of(ep), false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("Bad request"));
        assertTrue(ex.getMessage().contains("Empty endpoints"));
    }

    @Test
    public void testPostRequireBundle404UnknownVersionNamesThePin() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(404)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "9.9.9",
                        List.of(ep), false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("service@9.9.9"), ex.getMessage());
        assertTrue(ex.getMessage().contains("fix the 'version' pin"), ex.getMessage());
    }

    @Test
    public void testPostRequireBundle410DeliberatelyAbsent() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(410).withBody("{\"error\":\"missing endpoints: GET /api\"}")));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0",
                        List.of(ep), false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("deliberately"), "message should distinguish 410 from 404: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("Unexpected response"), "410 must not fall into the generic branch: " + ex.getMessage());
    }

    @Test
    public void testPostRequireBundleUnexpectedStatus() {
        wireMock.stubFor(post(urlEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(500)));

        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0",
                        List.of(ep), false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("Unexpected response 500"));
    }

    @Test
    public void testPostRequireBundleConnectionFailure() {
        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setMethod("GET");
        ep.setPath("/api");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                client.postRequireBundleWithEtag("http://localhost:1", null, "client", "service",
                        "1.0.0", List.of(ep), false, false, null, null, SanshainStream.none()));
        assertTrue(ex.getMessage().contains("Failed to connect"));
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
                "1.0.0", List.of(ep), false, false, null, "\"sha256:bundleHash\"", SanshainStream.none());

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
                "1.0.0", List.of(ep), false, false, null, null, SanshainStream.none());

        assertFalse(result.isNotModified());
        assertEquals("merged yaml", result.getContent());
        assertEquals("\"sha256:newBundleHash\"", result.getEtag());
    }

    @Test
    public void testRedirectSupport() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse()
                        .withStatus(308)
                        .withHeader("Location", baseUrl + "/new-require")));
        wireMock.stubFor(get(urlPathEqualTo("/new-require"))
                .willReturn(aResponse().withStatus(200).withBody("redirected yaml")));

        RequireResult result = client.getRequireWithEtag(baseUrl, null, "client", "service",
                "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none());

        assertEquals("redirected yaml", result.getContent());
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
            RequireResult result = insecureClient.getRequireWithEtag(httpsBaseUrl, null, "client", "service",
                    "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none());

            assertEquals("secure yaml", result.getContent());
        } finally {
            httpsMock.stop();
        }
    }

    @Test
    public void testAngryCatOnRequireError() {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        Log log = Mockito.mock(Log.class);
        SanshainHttpClient localClient = new SanshainHttpClient(log);

        assertThrows(MojoExecutionException.class, () ->
                localClient.getRequireWithEtag(baseUrl, null, "client", "service", "1.0.0", "/api", "GET", false, false, null, null, SanshainStream.none()));

        verify(log, atLeastOnce()).error(contains("NOT happy"));
    }

    // --- 2.2: streams, retire, harvested subscriptions, line endings ---

    /** A build that declares no stream must send neither field. */
    @Test
    public void testProvideOmitsStreamFieldsWhenUndeclared() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        provide("openapi");

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(notMatching(".*trunk.*"))
                .withRequestBody(notMatching(".*tag.*")));
    }

    @Test
    public void testProvideSendsTrunkWhenDeclared() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, "token123", "my-service", "openapi: 3.0.0",
                "snapshot", false, false, "openapi", "openapi.yaml", SanshainStream.trunk());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.trunk", equalTo("true"))));
    }

    @Test
    public void testProvideSendsTagWhenDeclared() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, "token123", "my-service", "openapi: 3.0.0",
                "snapshot", false, false, "openapi", "openapi.yaml", SanshainStream.tag("R"));

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.tag", equalTo("R"))));
    }

    @Test
    public void testRequireSendsStreamAsQueryParam() throws MojoExecutionException {
        wireMock.stubFor(get(urlPathEqualTo("/require"))
                .willReturn(aResponse().withStatus(200).withBody("paths: {}")));

        client.getRequireWithEtag(baseUrl, null, "client", "service", "1.0.0", "/api", "GET",
                false, false, null, null, SanshainStream.trunk());

        wireMock.verify(getRequestedFor(urlPathEqualTo("/require"))
                .withQueryParam("trunk", equalTo("true")));
    }

    /**
     * Sanshain compares content byte for byte, so a CRLF checkout must not hash
     * differently from an LF one — otherwise the same commit conflicts with
     * itself depending on which runner published it.
     */
    @Test
    public void testProvideNormalizesLineEndings() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        client.postProvide(baseUrl, null, "my-service", "openapi: 3.0.0\r\ninfo:\r\n  title: T\r",
                "snapshot", false, false, "openapi", "openapi.yaml", SanshainStream.none());

        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.openapi_yaml",
                        equalTo("openapi: 3.0.0\ninfo:\n  title: T\n"))));
    }

    @Test
    public void testNormalizeLineEndingsLeavesLfContentAlone() {
        String lf = "a\nb\n";
        assertSame(lf, SanshainHttpClient.normalizeLineEndings(lf));
        assertNull(SanshainHttpClient.normalizeLineEndings(null));
        assertEquals("a\nb\n", SanshainHttpClient.normalizeLineEndings("a\r\nb\r"));
    }

    /**
     * The remedy is a role grant, which is not something the Producer can change
     * in its own repository — so the message has to name it.
     */
    @Test
    public void testProvideForbiddenNamesTheReleaserRole() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(403)
                        .withBody("{\"error\":\"publishing GA for 'my-service' requires the 'releaser' role\"}")));

        MojoExecutionException e = assertThrows(MojoExecutionException.class, () -> provide("openapi"));
        assertTrue(e.getMessage().contains("releaser"), e.getMessage());
        assertTrue(e.getMessage().contains("snapshot"),
                "the message offers the fallback, got: " + e.getMessage());
    }

    // --- retire ---

    @Test
    public void testRetireSendsRetiredWithNoDocument() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide/asyncapi"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"tag_cleared\":\"messaging\",\"trunk_pins_closed\":2,"
                                + "\"contracts_released\":1}")));

        RetiredProtocol result = client.postRetire(baseUrl, "token123", "my-service", false, false, "asyncapi");

        assertNotNull(result);
        assertEquals("messaging", result.getTagCleared());
        assertEquals(2, result.getTrunkPinsClosed());
        assertEquals(1, result.getContractsReleased());
        wireMock.verify(postRequestedFor(urlEqualTo("/provide/asyncapi"))
                .withRequestBody(matchingJsonPath("$.retired", equalTo("true")))
                .withRequestBody(notMatching(".*asyncapi_yaml.*"))
                .withRequestBody(notMatching(".*stability.*")));
    }

    @Test
    public void testRetireDryRunIsSentAsSuch() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"trunk_pins_closed\":0,\"contracts_released\":0}")));

        RetiredProtocol result = client.postRetire(baseUrl, null, "my-service", false, true, "openapi");

        assertNotNull(result);
        assertNull(result.getTagCleared());
        wireMock.verify(postRequestedFor(urlEqualTo("/provide"))
                .withRequestBody(matchingJsonPath("$.dry_run", equalTo("true"))));
    }

    @Test
    public void testRetireForbiddenNamesBothWaysIn() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(403)
                        .withBody("{\"error\":\"retiring an API family requires the 'releaser' role\"}")));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> client.postRetire(baseUrl, null, "my-service", false, false, "openapi"));
        assertTrue(e.getMessage().contains("releaser"), e.getMessage());
        assertTrue(e.getMessage().contains("maintainer"), e.getMessage());
    }

    @Test
    public void testRetireUnknownProducerIsInstructive() {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"error\":\"Producer 'ghost' not found\"}")));

        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> client.postRetire(baseUrl, null, "ghost", false, false, "openapi"));
        assertTrue(e.getMessage().contains("no family to retire"), e.getMessage());
    }

    // --- harvested subscriptions ---

    @Test
    public void testHarvestedSubscriptionsAreParsed() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide/asyncapi"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\",\"stability\":\"snapshot\","
                                + "\"content_hash\":\"sha256:abc\","
                                + "\"changes\":{\"inserts\":0,\"updates\":0,\"deletes\":0},"
                                + "\"harvested_subscriptions\":["
                                + "{\"channel\":\"user/signup\",\"message_name\":\"UserSignedUp\","
                                + "\"owner\":\"accounts\"},"
                                + "{\"channel\":\"order/placed\",\"message_name\":\"OrderPlaced\","
                                + "\"drift\":\"expects field 'total' the contract does not guarantee\"}]}")));

        ProvideResponse response = provide("asyncapi");

        List<ProvideResponse.HarvestedSubscription> subs = response.getHarvestedSubscriptions();
        assertEquals(2, subs.size());

        assertEquals("user/signup", subs.get(0).channel);
        assertEquals("accounts", subs.get(0).owner);
        assertFalse(subs.get(0).isAdvisory(), "a resolved subscription is not an advisory");
        assertTrue(subs.get(0).describe().contains("accounts"));

        assertTrue(subs.get(1).isAdvisory(), "drift is an advisory");
        assertTrue(subs.get(1).describe().contains("no publisher yet"),
                "got: " + subs.get(1).describe());
        assertTrue(subs.get(1).describe().contains("total"), "got: " + subs.get(1).describe());
    }

    /** Absent for OpenAPI and for documents that declare none — never null. */
    @Test
    public void testHarvestedSubscriptionsDefaultToEmpty() throws MojoExecutionException {
        wireMock.stubFor(post(urlEqualTo("/provide"))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(PROVIDE_202_BODY)));

        assertTrue(provide("openapi").getHarvestedSubscriptions().isEmpty());
    }

    /**
     * The bundle endpoint reads the stream from the body — its handler has no
     * query extractor, so a stream on the query string would be silently
     * dropped and a trunk build would record no trunk pins.
     */
    @Test
    public void testRequireBundleCarriesStreamInTheBody() throws MojoExecutionException {
        wireMock.stubFor(post(urlPathEqualTo("/require-bundle"))
                .willReturn(aResponse().withStatus(200).withBody("paths: {}")));

        java.util.List<SanshainConfig.EndpointConfig> endpoints = new java.util.ArrayList<>();
        SanshainConfig.EndpointConfig ep = new SanshainConfig.EndpointConfig();
        ep.setPath("/api/v1/users");
        ep.setMethod("GET");
        endpoints.add(ep);
        SanshainConfig.EndpointConfig ep2 = new SanshainConfig.EndpointConfig();
        ep2.setPath("/api/v1/users/{id}");
        ep2.setMethod("GET");
        endpoints.add(ep2);

        client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0", endpoints,
                false, false, null, null, SanshainStream.trunk());
        wireMock.verify(postRequestedFor(urlPathEqualTo("/require-bundle"))
                .withRequestBody(matchingJsonPath("$.trunk", equalTo("true"))));

        wireMock.resetRequests();
        client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0", endpoints,
                false, false, null, null, SanshainStream.tag("R"));
        wireMock.verify(postRequestedFor(urlPathEqualTo("/require-bundle"))
                .withRequestBody(matchingJsonPath("$.tag", equalTo("R"))));

        wireMock.resetRequests();
        client.postRequireBundleWithEtag(baseUrl, null, "client", "service", "1.0.0", endpoints,
                false, false, null, null, SanshainStream.none());
        wireMock.verify(postRequestedFor(urlPathEqualTo("/require-bundle"))
                .withRequestBody(notMatching(".*trunk.*"))
                .withRequestBody(notMatching(".*tag.*")));
    }

    private static byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }
}
