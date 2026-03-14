package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SanshainConfig {
    private String sanshainUrl;
    private String clientName;
    private Integer timeout;
    private Boolean compression;
    private ProvideConfig provide;
    @JsonProperty("requires")
    private List<RequireConfig> requires;

    public String getSanshainUrl() { return sanshainUrl; }
    public void setSanshainUrl(String sanshainUrl) { this.sanshainUrl = sanshainUrl; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }
    public Boolean getCompression() { return compression; }
    public void setCompression(Boolean compression) { this.compression = compression; }
    public ProvideConfig getProvide() { return provide; }
    public void setProvide(ProvideConfig provide) { this.provide = provide; }
    public List<RequireConfig> getRequires() { return requires; }
    public void setRequires(List<RequireConfig> requires) { this.requires = requires; }

    public static class ProvideConfig {
        private String serviceName;
        private String openApiFile;
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getOpenApiFile() { return openApiFile; }
        public void setOpenApiFile(String openApiFile) { this.openApiFile = openApiFile; }
    }

    public static class RequireConfig {
        private String serviceName;
        private String outputDirectory;
        private Integer timeout;
        private List<EndpointConfig> endpoints;

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        public Integer getTimeout() { return timeout; }
        public void setTimeout(Integer timeout) { this.timeout = timeout; }
        public List<EndpointConfig> getEndpoints() { return endpoints; }
        public void setEndpoints(List<EndpointConfig> endpoints) { this.endpoints = endpoints; }
    }

    public static class EndpointConfig {
        private String method;
        private String path;
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
