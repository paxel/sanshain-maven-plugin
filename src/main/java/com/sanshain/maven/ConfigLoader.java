package com.sanshain.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;

public class ConfigLoader {

    public static SanshainConfig loadConfig(File configFile) throws MojoExecutionException {
        SanshainConfig config;
        if (configFile == null || !configFile.exists()) {
            config = new SanshainConfig();
        } else {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            try {
                config = mapper.readValue(configFile, SanshainConfig.class);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to load configuration from " + configFile.getAbsolutePath(), e);
            }
        }
        applyEnvOverrides(config);
        return config;
    }

    private static void applyEnvOverrides(SanshainConfig config) {
        String url = System.getenv("SANSHAIN_URL");
        if (url != null) {
            config.setSanshainUrl(url);
        }
        String clientName = System.getenv("SANSHAIN_CLIENT_NAME");
        if (clientName != null) {
            config.setClientName(clientName);
        }
        String timeout = System.getenv("SANSHAIN_TIMEOUT");
        if (timeout != null) {
            config.setTimeout(Integer.parseInt(timeout));
        }
        String compression = System.getenv("SANSHAIN_COMPRESSION");
        if (compression != null) {
            config.setCompression(Boolean.parseBoolean(compression));
        }
    }
}
