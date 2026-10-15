package com.sanshain.maven;

import org.apache.maven.monitor.logging.DefaultLog;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.mockito.Mockito;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
    public void testTrunkEnvSwitchValues() throws MojoFailureException {
        assertTrue(delegate(null, env("SANSHAIN_TRUNK", "1")).resolveStream(null, null).isTrunk());
        assertTrue(delegate(null, env("SANSHAIN_TRUNK", "TRUE")).resolveStream(null, null).isTrunk());
        assertTrue(delegate(null, env("SANSHAIN_TRUNK", " true ")).resolveStream(null, null).isTrunk());
        assertFalse(delegate(null, env("SANSHAIN_TRUNK", "yes")).resolveStream(null, null).isTrunk());
    }

    @Test
    public void testExplicitTrunkPropertyOverridesEnv() throws MojoFailureException {
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
        delegate.reportUnknownFields(configWithUnknown("serviceName: x\ncatalogInf: c.yaml\n").getUnknownFields());

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
                "requires:\n  - serviceName: y\n    version: 1.0.0\n    verison: 1.0.0\n").getUnknownFields());

        org.mockito.Mockito.verify(log).warn(
                "  ( O_o )   sanshain.yaml: unknown fields 'catalogInf', 'requires[0].verison' - ignored");
    }

    @Test
    public void testUnknownFieldUnderStrictFails() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, true);
        org.apache.maven.plugin.MojoFailureException ex = assertThrows(org.apache.maven.plugin.MojoFailureException.class,
                () -> delegate.reportUnknownFields(configWithUnknown("serviceName: x\nprovide:\n  fiel: a.yaml\n").getUnknownFields()));
        assertEquals("sanshain.yaml: unknown field 'provide.fiel' - rejected (strict)", ex.getMessage());
        org.mockito.Mockito.verify(log).warn("   /\\_/\\  ?");
        org.mockito.Mockito.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'provide.fiel' - rejected (strict)");
        org.mockito.Mockito.verify(log).warn(" ---U-U---");
    }

    @Test
    public void testNoUnknownFieldsNoCat() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, true);
        delegate.reportUnknownFields(configWithUnknown("serviceName: x\nforce: true\nstrict: true\n").getUnknownFields());
        org.mockito.Mockito.verifyNoInteractions(log);
    }

    // --- order: legacy rejection, then the cat, then the other checks ---

    private Path writeConfig(String yaml) throws IOException {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);
        return configPath;
    }

    private static final String TYPO_MISSING_VERSION = "serviceName: x\n" +
            "requires:\n" +
            "  - serviceName: users\n" +
            "    verison: 1.0.0\n" +
            "    endpoints:\n" +
            "      - method: GET\n" +
            "        path: /a\n";

    @Test
    public void testCatShowsBeforeLaterValidationError() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, false);
        Path configPath = writeConfig(TYPO_MISSING_VERSION);

        MojoExecutionException ex = assertThrows(MojoExecutionException.class,
                () -> new SanshainConfig().loadConfig(configPath.toFile(), delegate));
        assertTrue(ex.getMessage().contains("missing 'version'"), ex.getMessage());
        org.mockito.Mockito.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'requires[0].verison' - ignored");
    }

    @Test
    public void testStrictFailsOnUnknownBeforeLaterValidation() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, true);
        Path configPath = writeConfig(TYPO_MISSING_VERSION);

        org.apache.maven.plugin.MojoFailureException ex = assertThrows(org.apache.maven.plugin.MojoFailureException.class,
                () -> new SanshainConfig().loadConfig(configPath.toFile(), delegate));
        assertEquals("sanshain.yaml: unknown field 'requires[0].verison' - rejected (strict)", ex.getMessage());
    }

    @Test
    public void testLegacyFieldErrorsBeforeTheCat() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, true);
        Path configPath = writeConfig("serviceName: x\ncatalogInf: c.yaml\ntimeout: 30\n");

        MojoExecutionException ex = assertThrows(MojoExecutionException.class,
                () -> new SanshainConfig().loadConfig(configPath.toFile(), delegate));
        assertTrue(ex.getMessage().contains("'timeout' is no longer supported"), ex.getMessage());
        org.mockito.Mockito.verifyNoInteractions(log);
    }

    @Test
    public void testCatShowsWhenBindingFails() throws Exception {
        org.apache.maven.plugin.logging.Log log = org.mockito.Mockito.mock(org.apache.maven.plugin.logging.Log.class);
        SanshainMojoDelegate delegate = new SanshainMojoDelegate(log, null, null, false);
        Path configPath = writeConfig("serviceName: x\ncatalogInf: c.yaml\nrequires: not-a-list\n");

        assertThrows(MojoExecutionException.class, () -> new SanshainConfig().loadConfig(configPath.toFile(), delegate));
        org.mockito.Mockito.verify(log).warn("  ( O_o )   sanshain.yaml: unknown field 'catalogInf' - ignored");
    }

    // --- strict: explicit property > yaml strict > off ---

    @Test
    public void testStrictResolution() throws Exception {
        SanshainConfig yamlOn = loadConfig("serviceName: x\nstrict: true\n", Collections.emptyMap());
        SanshainConfig yamlOff = loadConfig("serviceName: x\nstrict: false\n", Collections.emptyMap());
        SanshainConfig yamlUnset = loadConfig("serviceName: x\n", Collections.emptyMap());

        SanshainMojoDelegate unset = new SanshainMojoDelegate(new DefaultLog(new ConsoleLogger()), null, null, null);
        unset.resolveStrict(yamlOn);
        assertTrue(unset.isStrict());

        SanshainMojoDelegate unset2 = new SanshainMojoDelegate(new DefaultLog(new ConsoleLogger()), null, null, null);
        unset2.resolveStrict(yamlUnset);
        assertFalse(unset2.isStrict());

        SanshainMojoDelegate off = new SanshainMojoDelegate(new DefaultLog(new ConsoleLogger()), null, null, false);
        off.resolveStrict(yamlOn);
        assertFalse(off.isStrict());

        SanshainMojoDelegate on = new SanshainMojoDelegate(new DefaultLog(new ConsoleLogger()), null, null, true);
        on.resolveStrict(yamlOff);
        assertTrue(on.isStrict());
    }

    private static final String BOTH = "trunk and tag are mutually exclusive - this build is recorded in neither";

    @Test
    public void testTrunkAndTagWarnsAndResolvesToNeither() throws Exception {
        Log log = Mockito.mock(Log.class);
        SanshainMojoDelegate d = new SanshainMojoDelegate(log, null, null, false);
        d.setEnvironmentVariables(env("SANSHAIN_TAG", "R"));
        SanshainStream stream = d.resolveStream(true, null);
        assertTrue(stream.isNone());
        assertEquals("", stream.toQueryParams());
        Mockito.verify(log).warn(BOTH);
    }

    @Test
    public void testTrunkAndTagUnderStrictFails() {
        Log log = Mockito.mock(Log.class);
        SanshainMojoDelegate d = new SanshainMojoDelegate(log, null, null, true);
        d.setEnvironmentVariables(Collections.emptyMap());
        MojoFailureException e = assertThrows(MojoFailureException.class, () -> d.resolveStream(true, "R"));
        assertEquals("strict: " + BOTH, e.getMessage());
        Mockito.verify(log).warn(BOTH);
    }

    @Test
    public void testWarningsWithoutStrictDoNothing() throws Exception {
        Log log = Mockito.mock(Log.class);
        new SanshainMojoDelegate(log, null, null, false)
                .failOnWarnings(Arrays.asList("a", "b"), new MojoExecutionException("boom"));
        Mockito.verifyNoInteractions(log);
    }

    @Test
    public void testStrictFailsWithTheFirstWarning() {
        SanshainMojoDelegate d = new SanshainMojoDelegate(Mockito.mock(Log.class), null, null, true);
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> d.failOnWarnings(Arrays.asList("first", "second"), null));
        assertEquals("strict: Sanshain warned: first", e.getMessage());
    }

    @Test
    public void testStrictWithoutWarningsPasses() throws Exception {
        new SanshainMojoDelegate(Mockito.mock(Log.class), null, null, true)
                .failOnWarnings(Collections.emptyList(), null);
    }

    @Test
    public void testStrictPrintsTheCallErrorBeforeFailing() {
        Log log = Mockito.mock(Log.class);
        SanshainMojoDelegate d = new SanshainMojoDelegate(log, null, null, true);
        assertThrows(MojoFailureException.class,
                () -> d.failOnWarnings(Collections.singletonList("w"), new MojoExecutionException("boom")));
        Mockito.verify(log).error("boom");
    }

    @Test
    public void testHandleExceptionNeverSoftensAStrictFailure() {
        SanshainMojoDelegate d = new SanshainMojoDelegate(Mockito.mock(Log.class), null, null, true);
        MojoFailureException strict = new MojoFailureException("strict: Sanshain warned: w");
        assertSame(strict, assertThrows(MojoFailureException.class, () -> d.handleException(strict, true)));
    }
}
