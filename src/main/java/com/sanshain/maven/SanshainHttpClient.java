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
import javax.net.ssl.X509TrustManager;
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

    /**
     * Uploads an OpenAPI specification to the Sanshain service.
     *
     * @param baseUrl     the base URL of the Sanshain service
     * @param token       the authentication token (optional)
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param openapiYaml the content of the OpenAPI specification
     * @param compression true if GZIP compression should be used
     * @throws MojoExecutionException if the request fails or is rejected
     */
    public void postProvide(String baseUrl, String token, String serviceName, String branch,
                            String openapiYaml, boolean compression, boolean dryRun) throws MojoExecutionException {
        String json = buildProvideJson(serviceName, branch, openapiYaml, dryRun);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/provide"))
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

        log.debug("POST " + baseUrl + "/provide");
        log.debug("Request body size: " + body.length + " bytes" + (compression ? " (gzip)" : ""));

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            log.debug("Response status: " + status);
            log.debug("Response body: " + response.body());
            if (status == 202) {
                log.info("Specification accepted by Sanshain service.");
            } else if (status == 400) {
                log.error("Provide failed (400 Bad Request): " + response.body());
                throw new MojoExecutionException("Bad request: " + response.body());
            } else if (status == 409) {
                log.error("Provide failed (409 Conflict): " + response.body());
                throw new MojoExecutionException("Conflict on protected branch: " + response.body());
            } else {
                log.error("Provide failed (" + status + "): " + response.body());
                throw new MojoExecutionException("Unexpected response " + status + ": " + response.body());
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to connect to Sanshain service at " + baseUrl, e);
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
     * @param clientName  the name of the client requesting the endpoint
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param path        the API path
     * @param method      the HTTP method
     * @param timeout     the timeout in seconds
     * @param compression true if GZIP compression should be used
     * @return the content of the downloaded OpenAPI snippet
     * @throws MojoExecutionException if the request fails or the endpoint is not found
     */
    public String getRequire(String baseUrl, String token, String clientName, String serviceName,
                             String branch, String path, String method, int timeout,
                             boolean compression, boolean dryRun) throws MojoExecutionException {
        String url = baseUrl + "/require?" +
                "clientname=" + urlEncode(clientName) +
                "&servicename=" + urlEncode(serviceName) +
                "&branch=" + urlEncode(branch) +
                "&path=" + urlEncode(path) +
                "&method=" + urlEncode(method) +
                "&timeout=" + timeout +
                "&dry_run=" + dryRun;

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

        log.debug("GET " + url);

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            log.debug("Response status: " + status + ", Content-Encoding: " + contentEncoding);
            log.debug("Response body size: " + response.body().length + " bytes");
            if (status == 200) {
                byte[] responseBody = response.body();
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    responseBody = gzipDecompress(responseBody);
                }
                return new String(responseBody, StandardCharsets.UTF_8);
            } else if (status == 404) {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
                log.error("Require failed (404 Not Found): " + errorBody);
                throw new MojoExecutionException(
                        "Endpoint not found: " + serviceName + " " + method + " " + path +
                        " (branch: " + branch + "): " + errorBody);
            } else {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
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
     * @param clientName  the name of the client requesting the endpoints
     * @param serviceName the name of the service providing the API
     * @param branch      the Git branch name
     * @param endpoints   the list of endpoints to request
     * @param timeout     the timeout in seconds
     * @param compression true if GZIP compression should be used
     * @return the merged OpenAPI YAML content
     * @throws MojoExecutionException if the request fails or endpoints are not found
     */
    public String postRequireBundle(String baseUrl, String token, String clientName, String serviceName,
                                    String branch, List<SanshainConfig.EndpointConfig> endpoints,
                                    int timeout, boolean compression, boolean dryRun) throws MojoExecutionException {
        String json = buildRequireBundleJson(clientName, serviceName, branch, endpoints, timeout, dryRun);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/require-bundle"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeout + 30));

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
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

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            log.debug("Response status: " + status + ", Content-Encoding: " + contentEncoding);
            log.debug("Response body size: " + response.body().length + " bytes");
            if (status == 200) {
                byte[] responseBody = response.body();
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    responseBody = gzipDecompress(responseBody);
                }
                return new String(responseBody, StandardCharsets.UTF_8);
            } else if (status == 400) {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
                log.error("Require-bundle failed (400 Bad Request): " + errorBody);
                throw new MojoExecutionException("Bad request to /require-bundle: " + errorBody);
            } else if (status == 404) {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
                log.error("Require-bundle failed (404 Not Found): " + errorBody);
                throw new MojoExecutionException(
                        "One or more endpoints not found for service: " + serviceName +
                        " (branch: " + branch + "): " + errorBody);
            } else {
                String errorBody = new String(response.body(), StandardCharsets.UTF_8);
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

    private String buildProvideJson(String serviceName, String branch, String openapiYaml, boolean dryRun) throws MojoExecutionException {
        ProvidePayload payload = new ProvidePayload();
        payload.servicename = serviceName;
        payload.branch = branch;
        payload.openapiYaml = openapiYaml;
        payload.dryRun = dryRun;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to build JSON for /provide", e);
        }
    }

    private String buildRequireBundleJson(String clientName, String serviceName, String branch,
                                          List<SanshainConfig.EndpointConfig> endpoints,
                                          int timeout, boolean dryRun) throws MojoExecutionException {
        RequireBundlePayload payload = new RequireBundlePayload();
        payload.clientname = clientName;
        payload.servicename = serviceName;
        payload.branch = branch;
        payload.timeout = timeout;
        payload.dryRun = dryRun;
        payload.endpoints = endpoints.stream()
                .map(ep -> {
                    RequireBundleEndpoint e = new RequireBundleEndpoint();
                    e.path = ep.getPath();
                    e.method = ep.getMethod();
                    return e;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to build JSON for /require-bundle", e);
        }
    }

    static class ProvidePayload {
        public String servicename;
        public String branch;
        @JsonProperty("openapi_yaml")
        public String openapiYaml;
        @JsonProperty("dry_run")
        public boolean dryRun;
    }

    static class RequireBundlePayload {
        public String clientname;
        public String servicename;
        public String branch;
        public List<RequireBundleEndpoint> endpoints;
        public int timeout;
        @JsonProperty("dry_run")
        public boolean dryRun;
    }

    static class RequireBundleEndpoint {
        public String path;
        public String method;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
