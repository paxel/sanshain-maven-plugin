package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * HTTP client for communicating with a Sanshain 2.x service.
 *
 * <p>Speaks only the 2.0 wire contract: provides carry a declared {@code stability}
 * ({@code snapshot}/{@code ga}) and read the version from the spec itself; requires pin an exact
 * {@code version}. There is no branch, no long-polling, and no optimistic concurrency.</p>
 */
public class SanshainHttpClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final Log log;
    private final ObjectMapper objectMapper;

    /** Memoized result of the lazy wrong-server diagnosis (one GET /version per client). */
    private boolean serverVersionChecked;
    private String pre2ServerVersion;

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
     * Provides a specification to the Sanshain service under its declared stability. The version
     * of the Provide is not a parameter — the server reads it from the spec content itself
     * ({@code info.version}, or the {@code // sanshain-version:} comment for proto).
     *
     * @param baseUrl      the base URL of the Sanshain service
     * @param token        the authentication token (optional)
     * @param producerName the name of the Producer providing the specification
     * @param content      the full specification content
     * @param stability    the declared stability ({@code snapshot} or {@code ga})
     * @param compression  true if GZIP compression should be used
     * @param dryRun       true to validate and classify without storing
     * @param apiType      the type of API (openapi, asyncapi, proto)
     * @param specFile     the spec file the content came from, named in 409 remediation hints
     * @param stream       the graph this build speaks for (trunk, a sanshain-branch, or neither)
     * @return the parsed provide response, or null if the 202 body could not be parsed
     * @throws MojoExecutionException if the request fails or is rejected
     */
    public ProvideResponse postProvide(String baseUrl, String token, String producerName, String content,
                            String stability, boolean compression, boolean dryRun, String apiType,
                            String specFile, SanshainStream stream) throws MojoExecutionException {
        String path = providePath(apiType);
        String contentField = contentField(apiType);
        String versionHint = versionHint(apiType);

        ProvidePayload payload = new ProvidePayload(producerName, normalizeLineEndings(content), stability,
                dryRun, contentField, stream);

        try {
            String json = objectMapper.writeValueAsString(payload);
            return postProvideInternal(baseUrl, path, token, json, compression, versionHint, specFile);
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to build JSON for " + path, e);
        }
    }

    /**
     * Declares that this Producer no longer provides an API family. The call is
     * an ordinary provide for that family carrying {@code retired: true} and no
     * document — the endpoint already names the family.
     *
     * <p>Retiring is a role: the caller needs {@code releaser} (which a build
     * pipeline already holds in order to publish GA) or a maintainer grant on
     * this Producer, or the server answers {@code 403}.
     *
     * @param baseUrl      the base URL of the Sanshain service
     * @param token        the authentication token (optional)
     * @param producerName the name of the Producer retiring the family
     * @param compression  true if GZIP compression should be used
     * @param dryRun       true to check permission without retiring anything
     * @param apiType      the type of API (openapi, asyncapi, proto)
     * @return what the retire shed, or null if the 202 body could not be parsed
     * @throws MojoExecutionException if the request fails or is rejected
     */
    public RetiredProtocol postRetire(String baseUrl, String token, String producerName, boolean compression,
                            boolean dryRun, String apiType) throws MojoExecutionException {
        String path = providePath(apiType);
        RetirePayload payload = new RetirePayload(producerName, dryRun);

        try {
            String json = objectMapper.writeValueAsString(payload);
            String body = postRetireInternal(baseUrl, path, token, json, compression);
            if (body == null) {
                return null;
            }
            return objectMapper.readValue(body, RetiredProtocol.class);
        } catch (JsonProcessingException e) {
            throw new MojoExecutionException("Failed to build JSON for " + path, e);
        } catch (IOException e) {
            log.debug("Could not parse retire response body: " + e.getMessage());
            return null;
        }
    }

    private static String providePath(String apiType) {
        if ("asyncapi".equalsIgnoreCase(apiType)) {
            return "/provide/asyncapi";
        }
        if ("proto".equalsIgnoreCase(apiType) || "grpc".equalsIgnoreCase(apiType)) {
            return "/provide/grpc";
        }
        return "/provide";
    }

    private static String contentField(String apiType) {
        if ("asyncapi".equalsIgnoreCase(apiType)) {
            return "asyncapi_yaml";
        }
        if ("proto".equalsIgnoreCase(apiType) || "grpc".equalsIgnoreCase(apiType)) {
            return "proto_content";
        }
        return "openapi_yaml";
    }

    private static String versionHint(String apiType) {
        if ("proto".equalsIgnoreCase(apiType) || "grpc".equalsIgnoreCase(apiType)) {
            return "the // sanshain-version: comment";
        }
        return "info.version";
    }

    /**
     * Sanshain compares provided content byte for byte, so a CRLF checkout of an
     * otherwise identical file hashes differently from an LF one and provokes a
     * spurious version conflict. Normalising here means a Windows and a Linux
     * runner publishing the same commit agree.
     *
     * @param content the specification as read from disk
     * @return the same content with LF line endings
     */
    static String normalizeLineEndings(String content) {
        if (content == null || content.indexOf('\r') < 0) {
            return content;
        }
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static class ProvidePayload {
        public final String producername;
        public final String stability;
        @JsonProperty("dry_run")
        public final boolean dryRun;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final String content;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final String contentField;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public final SanshainStream stream;

        public ProvidePayload(String producername, String content, String stability, boolean dryRun,
                              String contentField, SanshainStream stream) {
            this.producername = producername;
            this.content = content;
            this.stability = stability;
            this.dryRun = dryRun;
            this.contentField = contentField;
            this.stream = stream == null ? SanshainStream.none() : stream;
        }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public java.util.Map<String, Object> any() {
            java.util.Map<String, Object> any = new java.util.HashMap<>();
            any.put(contentField, content);
            // Omitted entirely when undeclared: the server defaults `trunk` to
            // false and treats an absent `tag` as "no branch", and sending
            // explicit nulls would be a 422 on a payload that denies unknown
            // shapes.
            if (stream.isTrunk()) {
                any.put("trunk", true);
            } else if (stream.getTag() != null) {
                any.put("tag", stream.getTag());
            }
            return any;
        }
    }

    /**
     * A retire carries no document and no stability — the server refuses a
     * request that says both {@code retired} and anything a publish needs, so
     * this payload has no field to hold them.
     */
    private static class RetirePayload {
        public final String producername;
        public final boolean retired = true;
        @JsonProperty("dry_run")
        public final boolean dryRun;

        public RetirePayload(String producername, boolean dryRun) {
            this.producername = producername;
            this.dryRun = dryRun;
        }
    }

    private ProvideResponse postProvideInternal(String baseUrl, String path, String token, String json,
                            boolean compression, String versionHint, String specFile) throws MojoExecutionException {
        String url = baseUrl + path;
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);

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
            }

            failOnPre2Server(baseUrl, token);

            if (status == 409) {
                ErrorBody error = parseErrorBody(responseBody);
                logAngryCat();
                log.error("Provide rejected by the version rules (409): " + sanitize(error.message(responseBody)));
                String message = "Provide rejected (409): " + sanitize(error.message(responseBody));
                if (error.proposedVersion != null) {
                    String remedy = "Publish as " + error.proposedVersion + " — update " + versionHint
                            + " in " + specFile + " and re-run.";
                    log.error(remedy);
                    message += " " + remedy;
                }
                throw new MojoExecutionException(message);
            } else if (status == 400) {
                logAngryCat();
                log.error("Provide failed (400 Bad Request): " + sanitize(responseBody));
                throw new MojoExecutionException("Invalid specification (400) — most commonly a missing or "
                        + "non-semver version (" + versionHint + "): " + sanitize(responseBody));
            } else if (status == 422) {
                logAngryCat();
                log.error("Provide failed (422 Unprocessable Entity): " + sanitize(responseBody));
                throw new MojoExecutionException("Request shape rejected (422) — the server rejects unknown "
                        + "or missing fields by name: " + sanitize(responseBody));
            } else if (status == 403) {
                logAngryCat();
                log.error("Provide refused (403 Forbidden): " + sanitize(responseBody));
                throw new MojoExecutionException(gaForbiddenMessage(responseBody));
            } else {
                logAngryCat();
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
     * Downloads an endpoint snippet at an exact pinned version, with ETag support.
     *
     * @param baseUrl      the base URL of the Sanshain service
     * @param token        the authentication token (optional)
     * @param consumerName the name of the requiring Consumer
     * @param producerName the name of the Producer to require from
     * @param version      the exact pinned version ({@code MAJOR.MINOR.PATCH})
     * @param path         the API path / channel / fully qualified gRPC service
     * @param method       the HTTP verb / PUB / SUB / RPC name
     * @param compression  true if GZIP compression should be used
     * @param dryRun       true to resolve without recording the dependency
     * @param apiType      the type of API (openapi, asyncapi, proto)
     * @param etag         the ETag from a previous response (optional, for If-None-Match)
     * @param stream       the graph this pin belongs to (trunk, a sanshain-branch, or neither)
     * @return the require result with content, ETag, and served stability
     * @throws MojoExecutionException if the request fails or the version/endpoint is unknown
     */
    public RequireResult getRequireWithEtag(String baseUrl, String token, String consumerName, String producerName,
                             String version, String path, String method,
                             boolean compression, boolean dryRun, String apiType, String etag,
                             SanshainStream stream) throws MojoExecutionException {
        String endpoint = "/require";
        if ("asyncapi".equalsIgnoreCase(apiType)) {
            endpoint = "/require/asyncapi";
        } else if ("proto".equalsIgnoreCase(apiType) || "grpc".equalsIgnoreCase(apiType)) {
            endpoint = "/require/grpc";
        }

        String url = baseUrl + endpoint + "?" +
                "consumername=" + urlEncode(consumerName) +
                "&producername=" + urlEncode(producerName) +
                "&version=" + urlEncode(version) +
                "&path=" + urlEncode(path) +
                "&method=" + urlEncode(method) +
                "&dry_run=" + dryRun
                + (stream == null ? "" : stream.toQueryParams());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT);

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
                String stability = response.headers().firstValue("X-Sanshain-Stability").orElse(null);
                return RequireResult.ok(extractResponseBody(response), responseEtag, stability);
            }

            failOnPre2Server(baseUrl, token);

            String errorBody = sanitize(extractResponseBody(response));
            if (status == 404) {
                logAngryCat();
                log.error("Require failed (404 Unknown): " + errorBody);
                throw new MojoExecutionException(
                        "Unknown producer or version: " + producerName + "@" + version +
                        " (" + method + " " + path + ") — fix the 'version' pin in sanshain.yaml" +
                        " (list available: GET /producers/" + producerName + "/versions): " + errorBody);
            } else if (status == 410) {
                logAngryCat();
                log.error("Require failed (410 Absent): " + errorBody);
                throw new MojoExecutionException(
                        "Endpoint absent: " + producerName + "@" + version + " exists but deliberately does not " +
                        "include " + method + " " + path + ": " + errorBody);
            } else {
                logAngryCat();
                log.error("Require failed (" + status + "): " + errorBody);
                throw new MojoExecutionException("Unexpected response " + status + " from " + endpoint + ": " + errorBody);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to connect to Sanshain service at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Request interrupted", e);
        }
    }

    /**
     * Requests a merged specification for multiple endpoints of one Producer at one pinned
     * version, with ETag support. Uses the /require-bundle endpoint which returns a single
     * document with deduplicated schemas.
     *
     * @param baseUrl      the base URL of the Sanshain service
     * @param token        the authentication token (optional)
     * @param consumerName the name of the requiring Consumer
     * @param producerName the name of the Producer to require from
     * @param version      the exact pinned version ({@code MAJOR.MINOR.PATCH})
     * @param endpoints    the list of endpoints to request
     * @param compression  true if GZIP compression should be used
     * @param dryRun       true to resolve without recording the dependency
     * @param apiType      the type of API (openapi, asyncapi, proto)
     * @param etag         the ETag from a previous response (optional, for If-None-Match)
     * @param stream       the graph these pins belong to (trunk, a sanshain-branch, or neither)
     * @return the require result with content, ETag, and served stability
     * @throws MojoExecutionException if the request fails or the version/endpoints are unknown
     */
    public RequireResult postRequireBundleWithEtag(String baseUrl, String token, String consumerName, String producerName,
                                    String version, List<SanshainConfig.EndpointConfig> endpoints,
                                    boolean compression, boolean dryRun, String apiType, String etag,
                                    SanshainStream stream) throws MojoExecutionException {
        String json = buildRequireBundleJson(consumerName, producerName, version, endpoints, dryRun, apiType);
        String streamParams = stream == null ? "" : stream.toQueryParams();
        String bundleUrl = baseUrl + "/require-bundle" + (streamParams.isEmpty() ? "" : "?" + streamParams.substring(1));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(bundleUrl))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);

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

        log.debug("POST " + bundleUrl);
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
                String stability = response.headers().firstValue("X-Sanshain-Stability").orElse(null);
                return RequireResult.ok(extractResponseBody(response), responseEtag, stability);
            }

            failOnPre2Server(baseUrl, token);

            String errorBody = sanitize(extractResponseBody(response));
            if (status == 400) {
                logAngryCat();
                log.error("Require-bundle failed (400 Bad Request): " + errorBody);
                throw new MojoExecutionException("Bad request to /require-bundle: " + errorBody);
            } else if (status == 404) {
                logAngryCat();
                log.error("Require-bundle failed (404 Unknown): " + errorBody);
                throw new MojoExecutionException(
                        "Unknown producer or version: " + producerName + "@" + version +
                        " — fix the 'version' pin in sanshain.yaml" +
                        " (list available: GET /producers/" + producerName + "/versions): " + errorBody);
            } else if (status == 410) {
                logAngryCat();
                log.error("Require-bundle failed (410 Absent): " + errorBody);
                throw new MojoExecutionException(
                        "Endpoints absent: " + producerName + "@" + version + " exists but deliberately does not " +
                        "include one or more requested endpoints: " + errorBody);
            } else {
                logAngryCat();
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

    /**
     * Lazy wrong-server diagnosis: on the first failure, one {@code GET /version} checks whether
     * the instance is pre-2.0. If it is, the confusing wire error is replaced with a clear
     * upgrade message. Never called before a failure (no preflight); the result — including a
     * negative one — is memoized so this costs at most one request per client instance.
     *
     * @param baseUrl the base URL of the Sanshain service
     * @param token   the authentication token (optional)
     * @throws MojoExecutionException if the instance reports a version below 2.0.0
     */
    private void failOnPre2Server(String baseUrl, String token) throws MojoExecutionException {
        String oldVersion = detectPre2Server(baseUrl, token);
        if (oldVersion != null) {
            throw new MojoExecutionException("Sanshain server at " + baseUrl + " is " + oldVersion
                    + "; this client requires Sanshain 2.x — upgrade the server.");
        }
    }

    private String detectPre2Server(String baseUrl, String token) {
        if (serverVersionChecked) {
            return pre2ServerVersion;
        }
        serverVersionChecked = true;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/version"))
                .GET()
                .timeout(Duration.ofSeconds(10));
        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            VersionBody versionBody = objectMapper.readValue(extractResponseBody(response), VersionBody.class);
            if (versionBody.version == null) {
                return null;
            }
            int major = Integer.parseInt(versionBody.version.split("\\.", 2)[0].trim());
            if (major < 2) {
                pre2ServerVersion = versionBody.version;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Could not determine server version: " + e.getMessage());
        }
        return pre2ServerVersion;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class VersionBody {
        @JsonProperty("version")
        public String version;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ErrorBody {
        @JsonProperty("error")
        public String error;
        @JsonProperty("proposed_version")
        public String proposedVersion;

        String message(String rawBody) {
            return error != null ? error : rawBody;
        }
    }

    private ErrorBody parseErrorBody(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, ErrorBody.class);
        } catch (Exception e) {
            log.debug("Could not parse error body as JSON: " + e.getMessage());
            return new ErrorBody();
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

    private void logAngryCat() {
        log.error("");
        log.error("           .__....._             _.....__,");
        log.error("            .\": ò :':           ;': ó :\".");
        log.error("            `. `-' .'.         .'. `-' .'");
        log.error("              `---'               `---'");
        log.error("");
        log.error("    _...----...      ...   ...      ...----..._");
        log.error(" .-'__..-\"\"'----    `.  `\"`  .'    ----'\"\"-..__`-.");
        log.error("'.-'   _.--\"\"\"'       `-._.-'       '\"\"\"--._   `-.`");
        log.error("'  .-\"'                  :                  `\"-.  `     HISSSSSSSS!");
        log.error("  '   `.              _.'\"'._              .'   `");
        log.error("        `.       ,.-'\"VvVvVvV\"'-.,       .'");
        log.error("          `.         ^   ^   ^         .'");
        log.error("            `-._                   _.-'");
        log.error("                `\"'--...___...--'\"`");
        log.error("");
        log.error("   Sanshain is NOT happy with this!");
        log.error("");
    }

    private String buildRequireBundleJson(String consumerName, String producerName, String version,
                                          List<SanshainConfig.EndpointConfig> endpoints,
                                          boolean dryRun, String apiType) throws MojoExecutionException {
        RequireBundlePayload payload = new RequireBundlePayload();
        payload.consumername = consumerName;
        payload.producername = producerName;
        payload.version = version;
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
        public String consumername;
        public String producername;
        public String version;
        public List<RequireBundleEndpoint> endpoints;
        @JsonProperty("dry_run")
        public boolean dryRun;
        @JsonProperty("api_type")
        public String apiType;
    }

    static class RequireBundleEndpoint {
        public String path;
        public String method;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Publishing GA is a permission, not merely an authenticated act. The build
     * fails either way; what the message has to supply is the remedy, because
     * the fix is an administrator granting a role and nothing the Producer can
     * change in its own repository.
     *
     * @param responseBody the server's refusal
     * @return the message to fail the build with
     */
    private String gaForbiddenMessage(String responseBody) {
        return "Refused (403): " + sanitize(responseBody)
                + " — publishing a GA version requires the 'releaser' role. Grant it to the user or "
                + "token this build authenticates as (administrators and root always hold it), or "
                + "publish as a snapshot by leaving sanshain.ga / SANSHAIN_GA unset.";
    }

    /**
     * Retiring answers a different object from a publish, so it does not reuse
     * the provide response parser; everything else about the exchange — the
     * refusals, the compression, the logging — is the same.
     *
     * @return the raw 202 body, or null if the server sent none
     */
    private String postRetireInternal(String baseUrl, String path, String token, String json,
                            boolean compression) throws MojoExecutionException {
        String url = baseUrl + path;
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);

        if (token != null && !token.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (compression) {
            body = gzipCompress(body);
            requestBuilder.header("Content-Encoding", "gzip");
        }
        requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));

        log.debug("POST " + url + " (retire)");
        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            String responseBody = extractResponseBody(response);
            log.debug("Response status: " + status);
            log.debug("Response body: " + sanitize(responseBody));

            if (status == 202) {
                return responseBody;
            }
            if (status == 403) {
                logAngryCat();
                log.error("Retire refused (403 Forbidden): " + sanitize(responseBody));
                throw new MojoExecutionException("Refused (403): " + sanitize(responseBody)
                        + " — retiring an API family requires the 'releaser' role or a maintainer grant "
                        + "on this Producer. Grant either to the user or token this build authenticates "
                        + "as; administrators and root always hold both.");
            }
            if (status == 404) {
                logAngryCat();
                log.error("Retire failed (404 Not Found): " + sanitize(responseBody));
                throw new MojoExecutionException("Unknown Producer (404): " + sanitize(responseBody)
                        + " — nothing was ever provided under this name, so there is no family to retire.");
            }
            logAngryCat();
            log.error("Retire failed (" + status + "): " + sanitize(responseBody));
            throw new MojoExecutionException("Unexpected response " + status + ": " + sanitize(responseBody));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to connect to Sanshain service at " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Request interrupted", e);
        }
    }
}
