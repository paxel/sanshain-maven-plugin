package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import javax.net.ssl.SSLEngine;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/**
 * HTTP client for communicating with the Sanshain service.
 */
public class SanshainHttpClient {

    private final HttpClient httpClient;
    private final Log log;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new SanshainHttpClient.
     * @param log the Maven log
     */
    public SanshainHttpClient(Log log) {
        this(log, false);
    }

    /**
     * Constructs a new SanshainHttpClient.
     * @param log      the Maven log
     * @param insecure true if SSL certificate errors should be ignored
     */
    public SanshainHttpClient(Log log, boolean insecure) {
        this.log = log;
        this.objectMapper = new ObjectMapper();
        this.httpClient = createHttpClient(insecure);
    }

    private HttpClient createHttpClient(boolean insecure) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (insecure) {
            log.warn("SSL certificate validation is disabled (insecure=true)");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, new TrustManager[]{new InsecureTrustManager()}, new SecureRandom());
                builder.sslContext(sslContext);
                SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                // Disable hostname verification
                sslParameters.setEndpointIdentificationAlgorithm(null);
                builder.sslParameters(sslParameters);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                log.error("Failed to initialize insecure SSL context", e);
            }
        }

        return builder.build();
    }

    private static class InsecureTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public ProvideResponse postProvide(String baseUrl, String token, String serviceName, String branch,
                            String content, boolean compression, boolean dryRun, Integer baseVersion, String apiType, boolean force) throws MojoExecutionException {
        String path = "/provide";
        String contentField = "openapi_yaml";
        String type = "openapi";
        
        if ("asyncapi".equalsIgnoreCase(apiType)) {
            path = "/provide/asyncapi";
            contentField = "asyncapi_yaml";
            type = "asyncapi";
        } else if ("proto".equalsIgnoreCase(apiType) || "grpc".equalsIgnoreCase(apiType)) {
            path = "/provide/grpc";
            contentField = "proto_content";
            type = "proto";
        }

        ProvideGenericPayload payload = new ProvideGenericPayload(serviceName, branch, content, dryRun, type, baseVersion, contentField, force);

        try {
            String json = objectMapper.writeValueAsString(payload);
            return postProvideInternal(baseUrl + path, token, json, compression);
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to build JSON for " + path, e);
        }
    }

    public ProvideResponse postProvide(String baseUrl, String token, String serviceName, String branch,
                            String openapiYaml, boolean compression, boolean dryRun, Integer baseVersion, boolean force) throws MojoExecutionException {
        return postProvide(baseUrl, token, serviceName, branch, openapiYaml, compression, dryRun, baseVersion, "openapi", force);
    }

    public ProvideResponse postProvide(String baseUrl, String token, String serviceName, String branch,
                            String openapiYaml, boolean compression, boolean dryRun) throws MojoExecutionException {
        return postProvide(baseUrl, token, serviceName, branch, openapiYaml, compression, dryRun, null, false);
    }

    public ProvideResponse postProvideAsyncApi(String baseUrl, String token, String serviceName, String branch,
                                    String asyncapiYaml, boolean compression, boolean dryRun, Integer baseVersion, boolean force) throws MojoExecutionException {
        return postProvide(baseUrl, token, serviceName, branch, asyncapiYaml, compression, dryRun, baseVersion, "asyncapi", force);
    }

    public ProvideResponse postProvideAsyncApi(String baseUrl, String token, String serviceName, String branch,
                                    String asyncapiYaml, boolean compression, boolean dryRun) throws MojoExecutionException {
        return postProvideAsyncApi(baseUrl, token, serviceName, branch, asyncapiYaml, compression, dryRun, null, false);
    }

    public ProvideResponse postProvideProto(String baseUrl, String token, String serviceName, String branch,
                                 String protoContent, boolean compression, boolean dryRun, Integer baseVersion, boolean force) throws MojoExecutionException {
        return postProvide(baseUrl, token, serviceName, branch, protoContent, compression, dryRun, baseVersion, "proto", force);
    }

    public ProvideResponse postProvideProto(String baseUrl, String token, String serviceName, String branch,
                                 String protoContent, boolean compression, boolean dryRun) throws MojoExecutionException {
        return postProvideProto(baseUrl, token, serviceName, branch, protoContent, compression, dryRun, null, false);
    }

    private static class ProvideGenericPayload {
        public final String servicename;
        public final String branch;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final String content;
        @JsonProperty("dry_run")
        public final boolean dryRun;
        @JsonProperty("api_type")
        public final String apiType;
        @JsonProperty("base_version")
        public final Integer baseVersion;
        public final boolean force;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final String contentField;

        public ProvideGenericPayload(String servicename, String branch, String content, boolean dryRun, String apiType, Integer baseVersion, String contentField, boolean force) {
            this.servicename = servicename;
            this.branch = branch;
            this.content = content;
            this.dryRun = dryRun;
            this.apiType = apiType;
            this.baseVersion = baseVersion;
            this.contentField = contentField;
            this.force = force;
        }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public java.util.Map<String, Object> any() {
            java.util.Map<String, Object> any = new java.util.HashMap<>();
            any.put(contentField, content);
            return any;
        }
    }

    private ProvideResponse postProvideInternal(String url, String token, String json, boolean compression) throws MojoExecutionException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (compression) {
            body = gzipCompress(body);
            requestBuilder.header("Content-Encoding", "gzip");
        }

        requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));

        log.debug("POST " + url);
        log.debug("Request body size: " + body.length + " bytes" + (compression ? " (gzip)" : ""));
        log.debug("Request body (uncompressed): " + json);

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String responseBody = extractResponseBody(response);
            log.debug("Response status: " + status);
            logResponseHeaders(response);
            log.debug("Response body: " + sanitize(responseBody));
            if (status == 202) {
                try {
                    ProvideResponse provideResponse = objectMapper.readValue(responseBody, ProvideResponse.class);
                    log.info(provideResponse.toSummary());
                    return provideResponse;
                } catch (Exception e) {
                    log.debug("Could not parse provide response body: " + e.getMessage());
                    log.info("Specification accepted by Sanshain service.");
                    return null;
                }
            } else if (status == 400) {
                log.error("Provide failed (400 Bad Request): " + sanitize(responseBody));
                throw new MojoExecutionException("Bad request: " + sanitize(responseBody));
            } else if (status == 409) {
                log.error("Provide failed (409 Conflict): " + sanitize(responseBody));
                throw new MojoExecutionException("Concurrent modification detected. Server version has advanced beyond your base_version. Re-run to fetch the latest state.");
            } else {
                log.error("Provide failed (" + status + "): " + sanitize(responseBody));
                throw new MojoExecutionException("Unexpected response " + status + ": " + sanitize(responseBody));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to connect to Sanshain service at " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Request interrupted", e);
        }
    }

    /**
     * Downloads an OpenAPI snippet for a specific endpoint from the Sanshain service.
     *
     * @param baseUrl     the base URL of the Sanshain service
     * @param token       the authentication token (optional)
     * @param serviceNameIdentifier  the name of the client requesting the endpoint
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param path        the API path
     * @param method      the HTTP method
     * @param timeout     the timeout in seconds
     * @param compression true if GZIP compression should be used
     * @param dryRun      true to perform a dry run without persisting
     * @param apiType     the type of API (openapi, asyncapi, proto)
     * @return the content of the downloaded OpenAPI snippet
     * @throws MojoExecutionException if the request fails or the endpoint is not found
     */
    public String getRequire(String baseUrl, String token, String serviceNameIdentifier, String serviceName,
                             String branch, String path, String method, int timeout,
                             boolean compression, boolean dryRun, String apiType) throws MojoExecutionException {
        RequireResult result = getRequireWithEtag(baseUrl, token, serviceNameIdentifier, serviceName,
                branch, path, method, timeout, compression, dryRun, apiType, null);
        return result.getContent();
    }

    /**
     * Downloads an OpenAPI snippet with ETag support.
     *
     * @param baseUrl     the base URL of the Sanshain service
     * @param token       the authentication token (optional)
     * @param serviceNameIdentifier  the name of the client requesting the endpoint
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param path        the API path
     * @param method      the HTTP method
     * @param timeout     the timeout in seconds
     * @param compression true if GZIP compression should be used
     * @param dryRun      true to perform a dry run without persisting
     * @param apiType     the type of API (openapi, asyncapi, proto)
     * @param etag        the ETag from a previous response (optional, for If-None-Match)
     * @return the require result with content and ETag
     * @throws MojoExecutionException if the request fails or the endpoint is not found
     */
    public RequireResult getRequireWithEtag(String baseUrl, String token, String serviceNameIdentifier, String serviceName,
                             String branch, String path, String method, int timeout,
                             boolean compression, boolean dryRun, String apiType, String etag) throws MojoExecutionException {
        String endpoint = "/require";
        if ("asyncapi".equalsIgnoreCase(apiType)) {
            endpoint = "/require/asyncapi";
        } else if ("proto".equalsIgnoreCase(apiType)) {
            endpoint = "/require/grpc";
        }

        String url = baseUrl + endpoint + "?" +
                "clientname=" + urlEncode(serviceNameIdentifier) +
                "&servicename=" + urlEncode(serviceName) +
                "&branch=" + urlEncode(branch) +
                "&path=" + urlEncode(path) +
                "&method=" + urlEncode(method) +
                "&timeout=" + timeout +
                "&dry_run=" + dryRun +
                "&api_type=" + urlEncode(apiType != null ? apiType : "openapi");

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(timeout + 30));

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }
        if (compression) {
            requestBuilder.header("Accept-Encoding", "gzip");
        }
        if (etag != null && !etag.isEmpty()) {
            requestBuilder.header("If-None-Match", etag);
        }

        log.debug("GET " + url);

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            log.debug("Response status: " + status + ", Content-Encoding: " + contentEncoding);
            logResponseHeaders(response);
            log.debug("Response body size: " + response.body().length + " bytes");
            if (status == 304) {
                return RequireResult.notModified();
            } else if (status == 200) {
                String responseEtag = response.headers().firstValue("ETag").orElse(null);
                return RequireResult.ok(extractResponseBody(response), responseEtag);
            } else if (status == 404) {
                String errorBody = sanitize(extractResponseBody(response));
                log.error("Require failed (404 Not Found): " + errorBody);
                throw new MojoExecutionException(
                        "Endpoint not found: " + serviceName + " " + method + " " + path +
                        " (branch: " + branch + "): " + errorBody);
            } else {
                String errorBody = sanitize(extractResponseBody(response));
                log.error("Require failed (" + status + "): " + errorBody);
                throw new MojoExecutionException("Unexpected response " + status + " from /require: " + errorBody);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to connect to Sanshain service at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Request interrupted", e);
        }
    }

    /**
     * Requests a merged OpenAPI specification for multiple endpoints from the same service.
     * Uses the /require-bundle endpoint which returns a single YAML with deduplicated schemas.
     *
     * @param baseUrl     the base URL of the Sanshain service
     * @param token       the authentication token (optional)
     * @param serviceNameIdentifier  the name of the client requesting the endpoints
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param endpoints   the list of endpoints to request
     * @param timeout     the timeout in seconds
     * @param compression true if GZIP compression should be used
     * @param dryRun      true to perform a dry run without persisting
     * @param apiType     the type of API (openapi, asyncapi, proto)
     * @return the merged OpenAPI YAML content
     * @throws MojoExecutionException if the request fails or endpoints are not found
     */
    public String postRequireBundle(String baseUrl, String token, String serviceNameIdentifier, String serviceName,
                                    String branch, List<SanshainConfig.EndpointConfig> endpoints,
                                    int timeout, boolean compression, boolean dryRun, String apiType) throws MojoExecutionException {
        RequireResult result = postRequireBundleWithEtag(baseUrl, token, serviceNameIdentifier, serviceName,
                branch, endpoints, timeout, compression, dryRun, apiType, null);
        return result.getContent();
    }

    /**
     * Requests a merged OpenAPI specification with ETag support.
     *
     * @param baseUrl     the base URL of the Sanshain service
     * @param token       the authentication token (optional)
     * @param serviceNameIdentifier  the name of the client requesting the endpoints
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param endpoints   the list of endpoints to request
     * @param timeout     the timeout in seconds
     * @param compression true if GZIP compression should be used
     * @param dryRun      true to perform a dry run without persisting
     * @param apiType     the type of API (openapi, asyncapi, proto)
     * @param etag        the ETag from a previous response (optional, for If-None-Match)
     * @return the require result with content and ETag
     * @throws MojoExecutionException if the request fails or endpoints are not found
     */
    public RequireResult postRequireBundleWithEtag(String baseUrl, String token, String serviceNameIdentifier, String serviceName,
                                    String branch, List<SanshainConfig.EndpointConfig> endpoints,
                                    int timeout, boolean compression, boolean dryRun, String apiType, String etag) throws MojoExecutionException {
        String json = buildRequireBundleJson(serviceNameIdentifier, serviceName, branch, endpoints, timeout, dryRun, apiType);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/require-bundle"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout + 30));

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }
        if (etag != null && !etag.isEmpty()) {
            requestBuilder.header("If-None-Match", etag);
        }

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (compression) {
            body = gzipCompress(body);
            requestBuilder.header("Content-Encoding", "gzip");
            requestBuilder.header("Accept-Encoding", "gzip");
        }

        requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));

        log.debug("POST " + baseUrl + "/require-bundle");
        log.debug("Request body size: " + body.length + " bytes" + (compression ? " (gzip)" : ""));
        log.debug("Request body (uncompressed): " + json);

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            log.debug("Response status: " + status + ", Content-Encoding: " + contentEncoding);
            logResponseHeaders(response);
            log.debug("Response body size: " + response.body().length + " bytes");
            if (status == 304) {
                return RequireResult.notModified();
            } else if (status == 200) {
                String responseEtag = response.headers().firstValue("ETag").orElse(null);
                return RequireResult.ok(extractResponseBody(response), responseEtag);
            } else if (status == 400) {
                String errorBody = sanitize(extractResponseBody(response));
                log.error("Require-bundle failed (400 Bad Request): " + errorBody);
                throw new MojoExecutionException("Bad request to /require-bundle: " + errorBody);
            } else if (status == 404) {
                String errorBody = sanitize(extractResponseBody(response));
                log.error("Require-bundle failed (404 Not Found): " + errorBody);
                throw new MojoExecutionException(
                        "One or more endpoints not found for service: " + serviceName +
                        " (branch: " + branch + "): " + errorBody);
            } else {
                String errorBody = sanitize(extractResponseBody(response));
                log.error("Require-bundle failed (" + status + "): " + errorBody);
                throw new MojoExecutionException("Unexpected response " + status + " from /require-bundle: " + errorBody);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to connect to Sanshain service at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Request interrupted", e);
        }
    }

    private static byte[] gzipCompress(byte[] data) throws MojoExecutionException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
                gzip.write(data);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to gzip compress data", e);
        }
    }

    private static byte[] gzipDecompress(byte[] data) throws MojoExecutionException {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            try (GZIPInputStream gzip = new GZIPInputStream(bis)) {
                return gzip.readAllBytes();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to gzip decompress data", e);
        }
    }

    private String extractResponseBody(HttpResponse<byte[]> response) throws MojoExecutionException {
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return "";
        }
        if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) {
            body = gzipDecompress(body);
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private void logResponseHeaders(HttpResponse<?> response) {
        response.headers().map().forEach((name, values) -> 
            values.forEach(value -> log.debug("Response header: " + name + ": " + value))
        );
    }

    private String sanitize(String text) {
        if (text == null) return "";
        String result = text.length() > 1000 ? text.substring(0, 1000) + "... (truncated)" : text;
        return result.replaceAll("[^\\p{Print}\\p{Space}]", "?");
    }

    private String buildRequireBundleJson(String clientName, String serviceName, String branch,
                                          List<SanshainConfig.EndpointConfig> endpoints,
                                          int timeout, boolean dryRun, String apiType) throws MojoExecutionException {
        RequireBundlePayload payload = new RequireBundlePayload();
        payload.clientname = clientName;
        payload.servicename = serviceName;
        payload.branch = branch;
        payload.timeout = timeout;
        payload.dryRun = dryRun;
        payload.apiType = apiType != null ? apiType : "openapi";
        payload.endpoints = endpoints.stream()
                .map(ep -> {
                    RequireBundleEndpoint e = new RequireBundleEndpoint();
                    e.path = ep.getPath();
                    e.method = ep.getMethod();
                    return e;
                })
                .collect(java.util.stream.Collectors.toList());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to build JSON for /require-bundle", e);
        }
    }

    static class RequireBundlePayload {
        public String clientname;
        public String servicename;
        public String branch;
        public List<RequireBundleEndpoint> endpoints;
        public int timeout;
        @JsonProperty("dry_run")
        public boolean dryRun;
        @JsonProperty("api_type")
        public String apiType;
    }

    static class RequireBundleEndpoint {
        public String path;
        public String method;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
