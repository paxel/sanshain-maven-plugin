package com.sanshain.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;

public class ConfigLoader {

    public static SanshainConfig loadConfig(File configFile) throws MojoExecutionException {
        if (configFile == null || !configFile.exists()) {
            return null;
        }

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try {
            return mapper.readValue(configFile, SanshainConfig.class);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to load configuration from " + configFile.getAbsolutePath(), e);
        }
    }
}
