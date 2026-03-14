package com.sanshain.maven;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/**
 * HTTP client for communicating with the Sanshain service.
 */
public class SanshainHttpClient {

    private final HttpClient httpClient;
    private final Log log;

    /**
     * Constructs a new SanshainHttpClient.
     * @param log the Maven log
     */
    public SanshainHttpClient(Log log) {
        this.httpClient = HttpClient.newBuilder().build();
        this.log = log;
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
                            String openapiYaml, boolean compression) throws MojoExecutionException {
        String json = "{" +
                "\"servicename\":" + jsonEscape(serviceName) + "," +
                "\"branch\":" + jsonEscape(branch) + "," +
                "\"openapi_yaml\":" + jsonEscape(openapiYaml) +
                "}";

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

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 202) {
                log.info("Specification accepted by Sanshain service.");
            } else if (status == 400) {
                throw new MojoExecutionException("Bad request: " + response.body());
            } else if (status == 409) {
                throw new MojoExecutionException("Conflict on protected branch: " + response.body());
            } else {
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
                             boolean compression) throws MojoExecutionException {
        String url = baseUrl + "/require?" +
                "clientname=" + urlEncode(clientName) +
                "&servicename=" + urlEncode(serviceName) +
                "&branch=" + urlEncode(branch) +
                "&path=" + urlEncode(path) +
                "&method=" + urlEncode(method) +
                "&timeout=" + timeout;

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

        try {
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 200) {
                byte[] responseBody = response.body();
                String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    responseBody = gzipDecompress(responseBody);
                }
                return new String(responseBody, StandardCharsets.UTF_8);
            } else if (status == 404) {
                throw new MojoExecutionException(
                        "Endpoint not found: " + serviceName + " " + method + " " + path +
                        " (branch: " + branch + ") — server timed out after " + timeout + "s");
            } else {
                throw new MojoExecutionException("Unexpected response " + status + " from /require");
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

    private static String jsonEscape(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
