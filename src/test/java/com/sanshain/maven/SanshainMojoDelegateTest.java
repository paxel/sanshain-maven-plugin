package com.sanshain.maven;

import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SanshainMojoDelegateTest {

    @TempDir
    Path tempDir;

    private SanshainMojoDelegate delegate(Settings settings, Map<String, String> env) {
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(new DefaultLog(new ConsoleLogger()), settings, null, false);
        delegate.setEnvironmentVariables(env);
        return delegate;
    }

    private SanshainConfig loadConfig(String yaml, Map<String, String> env) throws IOException, MojoExecutionException {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);
        SanshainConfig loader = new SanshainConfig();
        loader.setEnvironmentVariables(env);
        return loader.loadConfig(configPath.toFile());
    }

    private static Map<String, String> env(String name, String value) {
        Map<String, String> env = new HashMap<>();
        env.put(name, value);
        return env;
    }

    private static Settings settingsWithUrl(String url) {
        Settings settings = new Settings();
        Server server = new Server();
        server.setId("sanshain");
        Xpp3Dom config = new Xpp3Dom("configuration");
        Xpp3Dom urlNode = new Xpp3Dom("url");
        urlNode.setValue(url);
        config.addChild(urlNode);
        server.setConfiguration(config);
        settings.addServer(server);
        return settings;
    }

    // --- SANSHAIN_* switch parsing ---

    @Test
    public void testSwitchAcceptsOneTrueAnyCaseAndSurroundingBlanks() {
        assertTrue(SanshainMojoDelegate.isSwitchOn("1"));
        assertTrue(SanshainMojoDelegate.isSwitchOn("TRUE"));
        assertTrue(SanshainMojoDelegate.isSwitchOn(" true "));
        assertTrue(SanshainMojoDelegate.isSwitchOn("True"));
    }

    @Test
    public void testSwitchTreatsEverythingElseAsOff() {
        assertFalse(SanshainMojoDelegate.isSwitchOn("yes"));
        assertFalse(SanshainMojoDelegate.isSwitchOn("0"));
        assertFalse(SanshainMojoDelegate.isSwitchOn("false"));
        assertFalse(SanshainMojoDelegate.isSwitchOn(""));
        assertFalse(SanshainMojoDelegate.isSwitchOn(null));
    }

    @Test
    public void testGaEnvSwitchValues() {
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", "1")).resolveStability(false));
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", "TRUE")).resolveStability(false));
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", " true ")).resolveStability(false));
        assertEquals("snapshot", delegate(null, env("SANSHAIN_GA", "yes")).resolveStability(false));
    }

    @Test
    public void testTrunkEnvSwitchValues() throws MojoExecutionException {
        assertTrue(delegate(null, env("SANSHAIN_TRUNK", "1")).resolveStream(null, null).isTrunk());
        assertTrue(delegate(null, env("SANSHAIN_TRUNK", "TRUE")).resolveStream(null, null).isTrunk());
        assertTrue(delegate(null, env("SANSHAIN_TRUNK", " true ")).resolveStream(null, null).isTrunk());
        assertFalse(delegate(null, env("SANSHAIN_TRUNK", "yes")).resolveStream(null, null).isTrunk());
    }

    @Test
    public void testExplicitTrunkPropertyOverridesEnv() throws MojoExecutionException {
        assertFalse(delegate(null, env("SANSHAIN_TRUNK", "1")).resolveStream(false, null).isTrunk());
    }

    @Test
    public void testBestEffortEnvSwitchValues() {
        assertTrue(delegate(null, env("SANSHAIN_BEST_EFFORT", "1")).resolveBestEffort(null, null));
        assertTrue(delegate(null, env("SANSHAIN_BEST_EFFORT", "TRUE")).resolveBestEffort(null, null));
        assertTrue(delegate(null, env("SANSHAIN_BEST_EFFORT", " true ")).resolveBestEffort(null, null));
        assertFalse(delegate(null, env("SANSHAIN_BEST_EFFORT", "yes")).resolveBestEffort(null, null));
    }

    @Test
    public void testExplicitBestEffortPropertyOverridesEnv() {
        assertFalse(delegate(null, env("SANSHAIN_BEST_EFFORT", "1")).resolveBestEffort(false, null));
    }

    @Test
    public void testConfigEnvSwitchesUseTheSameParser() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_BEST_EFFORT", " TRUE ");
        env.put("SANSHAIN_COMPRESSION", "yes");
        env.put("SANSHAIN_INSECURE", "1");
        env.put("SANSHAIN_COMBINE", "0");
        SanshainConfig config = loadConfig("serviceName: x\n", env);
        assertTrue(config.getBestEffort());
        assertFalse(config.getCompression());
        assertTrue(config.getInsecure());
        assertFalse(config.getCombine());
    }

    // --- SANSHAIN_URL precedence: property > settings.xml > SANSHAIN_URL > yaml ---

    @Test
    public void testSanshainUrlEnvOverridesYaml() throws Exception {
        SanshainConfig config = loadConfig("sanshainUrl: http://yaml\n", env("SANSHAIN_URL", "http://env"));
        assertEquals("http://env", delegate(null, Collections.emptyMap()).resolveUrl(null, config));
    }

    @Test
    public void testYamlUrlUsedWithoutEnv() throws Exception {
        SanshainConfig config = loadConfig("sanshainUrl: http://yaml\n", Collections.emptyMap());
        assertEquals("http://yaml", delegate(null, Collections.emptyMap()).resolveUrl(null, config));
    }

    @Test
    public void testSettingsUrlOverridesSanshainUrlEnv() throws Exception {
        SanshainConfig config = loadConfig("sanshainUrl: http://yaml\n", env("SANSHAIN_URL", "http://env"));
        assertEquals("http://settings", delegate(settingsWithUrl("http://settings"), Collections.emptyMap())
                .resolveUrl(null, config));
    }

    @Test
    public void testExplicitPropertyOverridesSanshainUrlEnv() throws Exception {
        SanshainConfig config = loadConfig("sanshainUrl: http://yaml\n", env("SANSHAIN_URL", "http://env"));
        assertEquals("http://property", delegate(settingsWithUrl("http://settings"), Collections.emptyMap())
                .resolveUrl("http://property", config));
    }

    // --- the puzzled cat ---

    private SanshainConfig configWithUnknown(String yaml) throws Exception {
        return loadConfig(yaml, Collections.emptyMap());
    }

    @Test
    public void testSingleUnknownFieldPrintsTheCat() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, false);
        delegate.reportUnknownFields(configWithUnknown("serviceName: x\ncatalogInf: c.yaml\n"));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(log);
        inOrder.verify(log).warn("   /\\_/\\  ?");
        inOrder.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'catalogInf' - ignored");
        inOrder.verify(log).warn(" ---U-U---");
        org.mockito.Mockito.verifyNoMoreInteractions(log);
    }

    @Test
    public void testSeveralUnknownFieldsInOneLine() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, false);
        delegate.reportUnknownFields(configWithUnknown("serviceName: x\ncatalogInf: c.yaml\n" +
                "requires:\n  - serviceName: y\n    version: 1.0.0\n    verison: 1.0.0\n"));

        org.mockito.Mockito.verify(log).warn(
                "  ( O_o )   sanshain.yaml: unknown fields 'catalogInf', 'requires[0].verison' - ignored");
    }

    @Test
    public void testUnknownFieldUnderStrictFails() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, true);
        org.apache.maven.plugin.MojoFailureException ex = assertThrows(org.apache.maven.plugin.MojoFailureException.class,
                () -> delegate.reportUnknownFields(configWithUnknown("serviceName: x\nprovide:\n  fiel: a.yaml\n")));
        assertEquals("sanshain.yaml: unknown field 'provide.fiel' - rejected (strict)", ex.getMessage());
        org.mockito.Mockito.verify(log).warn("   /\\_/\\  ?");
        org.mockito.Mockito.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'provide.fiel' - rejected (strict)");
        org.mockito.Mockito.verify(log).warn(" ---U-U---");
    }

    @Test
    public void testNoUnknownFieldsNoCat() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, true);
        delegate.reportUnknownFields(configWithUnknown("serviceName: x\nforce: true\nstrict: true\n"));
        org.mockito.Mockito.verifyNoInteractions(log);
    }
}
