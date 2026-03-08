package com.sanshain.maven;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SanshainConfig {
    private String sanshainUrl;
    private ProvideConfig provide;
    private List<RequireConfig> require;

    public String getSanshainUrl() { return sanshainUrl; }
    public void setSanshainUrl(String sanshainUrl) { this.sanshainUrl = sanshainUrl; }

    public ProvideConfig getProvide() { return provide; }
    public void setProvide(ProvideConfig provide) { this.provide = provide; }

    public List<RequireConfig> getRequire() { return require; }
    public void setRequire(List<RequireConfig> require) { this.require = require; }

    public static class ProvideConfig {
        private String serviceName;
        private String branch;
        private String openApiFile;

        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }

        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }

        public String getOpenApiFile() { return openApiFile; }
        public void setOpenApiFile(String openApiFile) { this.openApiFile = openApiFile; }
    }

    public static class RequireConfig {
        private String clientName;
        private List<RequireMojo.EndpointRequirement> requirements;
        private String outputDirectory;

        public String getClientName() { return clientName; }
        public void setClientName(String clientName) { this.clientName = clientName; }

        public List<RequireMojo.EndpointRequirement> getRequirements() { return requirements; }
        public void setRequirements(List<RequireMojo.EndpointRequirement> requirements) { this.requirements = requirements; }

        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
    }
}
