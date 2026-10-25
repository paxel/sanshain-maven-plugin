package com.sanshain.maven;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugin runs inside other people's builds, so the Maven and Java versions it
 * demands are a promise. maven-plugin-plugin derives them from the Maven API the
 * plugin compiles against: raising {@code maven-plugin-api.version} raises the floor
 * for every user. 2.3.0 and 2.4.0 shipped requiring Maven 3.9.16 that way.
 */
public class PluginDescriptorTest {

    private static String descriptorField(String name) throws IOException {
        try (InputStream in = PluginDescriptorTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertNotNull(in, "plugin.xml is generated before the tests run");
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
            assertTrue(m.find(), name + " missing from plugin.xml");
            return m.group(1);
        }
    }

    @Test
    public void runsOnMaven363AndLater() throws IOException {
        assertEquals("3.6.3", descriptorField("requiredMavenVersion"));
    }

    @Test
    public void runsOnJava11AndLater() throws IOException {
        assertEquals("11", descriptorField("requiredJavaVersion"));
    }
}
