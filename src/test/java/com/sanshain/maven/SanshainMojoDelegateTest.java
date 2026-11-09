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
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", "1")).resolveStability(null, SanshainStream.none(), null));
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", "TRUE")).resolveStability(null, SanshainStream.none(), null));
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", " true ")).resolveStability(null, SanshainStream.none(), null));
        assertEquals("snapshot", delegate(null, env("SANSHAIN_GA", "yes")).resolveStability(null, SanshainStream.none(), null));
    }

    // --- autoGa: the ga flag, then SANSHAIN_GA, then autoGa with a declared stream, then snapshot ---

    private static SanshainConfig autoGa(Boolean autoGa) {
        SanshainConfig config = new SanshainConfig();
        config.setAutoGa(autoGa);
        return config;
    }

    private static final Map<String, String> NO_ENV = Collections.emptyMap();

    @Test
    public void testAutoGaOnTrunkIsGa() {
        assertEquals("ga", delegate(null, NO_ENV).resolveStability(null, SanshainStream.trunk(), autoGa(true)));
    }

    @Test
    public void testAutoGaOnTagIsGa() {
        assertEquals("ga", delegate(null, NO_ENV).resolveStability(null, SanshainStream.tag("release-1"), autoGa(true)));
    }

    @Test
    public void testAutoGaWithoutStreamIsSnapshot() {
        assertEquals("snapshot", delegate(null, NO_ENV).resolveStability(null, SanshainStream.none(), autoGa(true)));
    }

    @Test
    public void testAutoGaOnTrunkWithEnvGaFalseIsSnapshot() {
        assertEquals("snapshot", delegate(null, env("SANSHAIN_GA", "false"))
                .resolveStability(null, SanshainStream.trunk(), autoGa(true)));
    }

    @Test
    public void testAutoGaOnTrunkWithGaFlagFalseIsSnapshot() {
        assertEquals("snapshot", delegate(null, NO_ENV).resolveStability(false, SanshainStream.trunk(), autoGa(true)));
    }

    @Test
    public void testAutoGaFalseOnTrunkIsSnapshot() {
        assertEquals("snapshot", delegate(null, NO_ENV).resolveStability(null, SanshainStream.trunk(), autoGa(false)));
    }

    @Test
    public void testAutoGaAbsentWithEnvGaTrueIsGa() {
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", "true"))
                .resolveStability(null, SanshainStream.none(), autoGa(null)));
    }

    @Test
    public void testGaFlagTrueWinsOverEnvGaFalseWithAutoGa() {
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", "false"))
                .resolveStability(true, SanshainStream.none(), autoGa(true)));
    }

    @Test
    public void testAutoGaWithTrunkAndTagIsGa() throws MojoFailureException {
        SanshainMojoDelegate d = delegate(null, env("SANSHAIN_TAG", "release-1"));
        SanshainStream both = d.resolveStream(true, null);
        assertTrue(both.isNone());
        assertEquals("ga", d.resolveStability(null, both, autoGa(true)));
    }

    @Test
    public void testBlankEnvGaIsUnsetSoAutoGaDecides() {
        assertEquals("ga", delegate(null, env("SANSHAIN_GA", " "))
                .resolveStability(null, SanshainStream.trunk(), autoGa(true)));
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
    public void testDefaultUrlIsTheServicePort() throws Exception {
        SanshainConfig config = loadConfig("serviceName: my-service\n", Collections.emptyMap());
        assertEquals("http://localhost:3000", delegate(null, Collections.emptyMap()).resolveUrl(null, config));
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

    // --- annotations: -DNAME (user property, then system property), then SANSHAIN_SET_NAME ---

    private static SanshainConfig annotated(String value) {
        return annotatedConfig(new SanshainConfig.AnnotationConfig("version", "requirements", value));
    }

    private static SanshainConfig annotatedConfig(SanshainConfig.AnnotationConfig... entries) {
        SanshainConfig config = Mockito.spy(new SanshainConfig());
        Mockito.doReturn(Arrays.asList(entries)).when(config).getAnnotations();
        return config;
    }

    private static java.util.Properties props(String name, String value) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty(name, value);
        return props;
    }

    private static final String LEFT_OUT = "annotation 'requirements' left out: {{ID}} is not set (-DID=… or SANSHAIN_SET_ID)";

    @Test
    public void testUserPropertyWinsOverSystemPropertyAndEnv() throws Exception {
        SanshainMojoDelegate d = delegate(null, env("SANSHAIN_SET_ID", "env"));
        d.setSystemProperties(props("ID", "system"));
        SanshainAnnotations a = d.resolveAnnotations(annotated("r-{{ID}}"), props("ID", "user"));
        assertEquals("r-user", a.forProvide().get(0).get("value"));
    }

    @Test
    public void testSystemPropertyWinsOverEnv() throws Exception {
        SanshainMojoDelegate d = delegate(null, env("SANSHAIN_SET_ID", "env"));
        d.setSystemProperties(props("ID", "system"));
        assertEquals("r-system", d.resolveAnnotations(annotated("r-{{ID}}"), null).forProvide().get(0).get("value"));
    }

    @Test
    public void testEnvFillsWhenNoPropertyIsSet() throws Exception {
        SanshainMojoDelegate d = delegate(null, env("SANSHAIN_SET_ID", "env"));
        d.setSystemProperties(new java.util.Properties());
        assertEquals("r-env", d.resolveAnnotations(annotated("r-{{ID}}"), new java.util.Properties())
                .forProvide().get(0).get("value"));
    }

    @Test
    public void testUnfilledAnnotationWarnsAndKeepsTheOthers() throws Exception {
        Log log = Mockito.mock(Log.class);
        SanshainMojoDelegate d = new SanshainMojoDelegate(log, null, null, false);
        d.setEnvironmentVariables(NO_ENV);
        d.setSystemProperties(new java.util.Properties());
        SanshainAnnotations a = d.resolveAnnotations(annotatedConfig(
                new SanshainConfig.AnnotationConfig("version", "requirements", "r-{{ID}}"),
                new SanshainConfig.AnnotationConfig("system", "jenkins", "https://jenkins.corp/job/payments")), null);
        Mockito.verify(log).warn(LEFT_OUT);
        assertEquals(1, a.forProvide().size());
        assertEquals("jenkins", a.forProvide().get(0).get("name"));
    }

    @Test
    public void testUnfilledAnnotationUnderStrictFails() {
        Log log = Mockito.mock(Log.class);
        SanshainMojoDelegate d = new SanshainMojoDelegate(log, null, null, true);
        d.setEnvironmentVariables(NO_ENV);
        d.setSystemProperties(new java.util.Properties());
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> d.resolveAnnotations(annotated("r-{{ID}}"), null));
        assertEquals("strict: " + LEFT_OUT, e.getMessage());
        Mockito.verify(log).warn(LEFT_OUT);
    }

    // --- A missing or broken sanshain.yaml ---

    private static final String INVALID_YAML = "invalid: yaml: : content";
    private static final String LEGACY_YAML = "serviceName: s\nprovide:\n  openApiFile: o.yaml\n  branch: main\n";

    private SanshainMojoDelegate delegate(Log log, Boolean strict, Map<String, String> env) {
        SanshainMojoDelegate d = new SanshainMojoDelegate(log, null, null, strict);
        d.setEnvironmentVariables(env);
        return d;
    }

    private java.io.File configFile(String yaml) throws IOException {
        Path configPath = tempDir.resolve("sanshain.yaml");
        Files.writeString(configPath, yaml);
        return configPath.toFile();
    }

    @Test
    public void testBrokenFileFailsWithoutBestEffort() throws Exception {
        java.io.File file = configFile(INVALID_YAML);
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> delegate(Mockito.mock(Log.class), null, NO_ENV).loadConfig(file, null));
        assertEquals("Failed to load configuration from " + file.getAbsolutePath(), e.getMessage());
    }

    @Test
    public void testBrokenFileIsSkippedWithTheBestEffortProperty() throws Exception {
        java.io.File file = configFile(INVALID_YAML);
        Log log = Mockito.mock(Log.class);
        assertTrue(delegate(log, null, NO_ENV).loadConfig(file, true).isEmpty());
        Mockito.verify(log).warn("Sanshain goal skipped (best effort): Failed to load configuration from " + file.getAbsolutePath());
    }

    @Test
    public void testBrokenFileIsSkippedWithTheBestEffortVariable() throws Exception {
        java.io.File file = configFile(INVALID_YAML);
        Log log = Mockito.mock(Log.class);
        assertTrue(delegate(log, null, env("SANSHAIN_BEST_EFFORT", "1")).loadConfig(file, null).isEmpty());
        Mockito.verify(log).warn("Sanshain goal skipped (best effort): Failed to load configuration from " + file.getAbsolutePath());
    }

    @Test
    public void testBestEffortPropertyFalseBeatsTheVariableForABrokenFile() throws Exception {
        java.io.File file = configFile(INVALID_YAML);
        assertThrows(MojoExecutionException.class,
                () -> delegate(Mockito.mock(Log.class), null, env("SANSHAIN_BEST_EFFORT", "true")).loadConfig(file, false));
    }

    @Test
    public void testLegacyFieldIsSkippedWithTheBestEffortVariable() throws Exception {
        java.io.File file = configFile(LEGACY_YAML);
        Log log = Mockito.mock(Log.class);
        assertTrue(delegate(log, null, env("SANSHAIN_BEST_EFFORT", "true")).loadConfig(file, null).isEmpty());
        Mockito.verify(log).warn(Mockito.startsWith("Sanshain goal skipped (best effort): Invalid sanshain.yaml for Sanshain 2.0:"));
    }

    @Test
    public void testBrokenFilesOwnBestEffortDoesNotCount() throws Exception {
        java.io.File file = configFile("bestEffort: true\n" + LEGACY_YAML);
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> delegate(Mockito.mock(Log.class), null, NO_ENV).loadConfig(file, null));
        assertTrue(e.getMessage().contains("'branch' is no longer supported"), e.getMessage());
    }

    @Test
    public void testStrictPropertyWinsOverBestEffortForABrokenFile() throws Exception {
        java.io.File invalid = configFile(INVALID_YAML);
        assertThrows(MojoExecutionException.class,
                () -> delegate(Mockito.mock(Log.class), true, NO_ENV).loadConfig(invalid, true));
        java.io.File legacy = configFile(LEGACY_YAML);
        assertThrows(MojoExecutionException.class,
                () -> delegate(Mockito.mock(Log.class), true, env("SANSHAIN_BEST_EFFORT", "1")).loadConfig(legacy, null));
    }

    @Test
    public void testBrokenFilesOwnStrictDoesNotCount() throws Exception {
        java.io.File file = configFile("strict: true\n" + LEGACY_YAML);
        assertTrue(delegate(Mockito.mock(Log.class), null, NO_ENV).loadConfig(file, true).isEmpty());
    }

    @Test
    public void testUnknownFieldUnderStrictFailsEvenWhenBestEffortSkipsBrokenFiles() throws Exception {
        java.io.File file = configFile("strict: true\nserviceName: s\ncatalogInf: c.yaml\n");
        MojoFailureException e = assertThrows(MojoFailureException.class,
                () -> delegate(Mockito.mock(Log.class), null, NO_ENV).loadConfig(file, true));
        assertEquals("sanshain.yaml: unknown field 'catalogInf' - rejected (strict)", e.getMessage());
    }

    @Test
    public void testValidFileLoads() throws Exception {
        java.io.File file = configFile("serviceName: s\n");
        SanshainConfig config = delegate(Mockito.mock(Log.class), null, NO_ENV).loadConfig(file, true).orElseThrow();
        assertEquals("s", config.getServiceName());
        assertNull(config.getMissingFile());
    }

    @Test
    public void testMissingFileLoadsAnEmptyConfigNamingTheFile() throws Exception {
        java.io.File file = tempDir.resolve("sanshain.yaml").toFile();
        SanshainConfig config = delegate(Mockito.mock(Log.class), null, NO_ENV).loadConfig(file, null).orElseThrow();
        assertEquals(file.getAbsoluteFile(), config.getMissingFile());
        assertNull(config.getServiceName());
    }

    private SanshainConfig missingConfig() throws Exception {
        return new SanshainConfig().loadConfig(tempDir.resolve("sanshain.yaml").toFile());
    }

    private String missingReason(String message) {
        return "Configuration file " + tempDir.resolve("sanshain.yaml").toFile().getAbsolutePath() + " not found: " + message;
    }

    @Test
    public void testMissingConfigurationWithoutTheFileFails() throws Exception {
        Log log = Mockito.mock(Log.class);
        SanshainConfig config = missingConfig();
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> delegate(log, null, NO_ENV).abort("serviceName is required", false, config));
        assertEquals(missingReason("serviceName is required"), e.getMessage());
        Mockito.verifyNoInteractions(log);
    }

    @Test
    public void testMissingConfigurationWithoutTheFileIsSkippedWithBestEffort() throws Exception {
        Log log = Mockito.mock(Log.class);
        delegate(log, null, NO_ENV).abort("serviceName is required", true, missingConfig());
        Mockito.verify(log).warn("Sanshain goal skipped (best effort): " + missingReason("serviceName is required"));
    }

    @Test
    public void testMissingConfigurationWithoutTheFileIsSkippedWithTheBestEffortVariable() throws Exception {
        Log log = Mockito.mock(Log.class);
        SanshainMojoDelegate d = delegate(log, null, env("SANSHAIN_BEST_EFFORT", "true"));
        SanshainConfig config = missingConfig();
        d.abort("serviceName is required", d.resolveBestEffort(null, config), config);
        Mockito.verify(log).warn("Sanshain goal skipped (best effort): " + missingReason("serviceName is required"));
    }

    @Test
    public void testMissingConfigurationWithoutTheFileFailsUnderStrictEvenWithBestEffort() throws Exception {
        SanshainConfig config = missingConfig();
        MojoExecutionException e = assertThrows(MojoExecutionException.class,
                () -> delegate(Mockito.mock(Log.class), true, NO_ENV).abort("serviceName is required", true, config));
        assertEquals(missingReason("serviceName is required"), e.getMessage());
    }

    @Test
    public void testMissingConfigurationInAnExistingFileWarnsAndSkips() throws Exception {
        Log log = Mockito.mock(Log.class);
        delegate(log, null, NO_ENV).abort("serviceName is required", false, loadConfig("compression: true\n", NO_ENV));
        Mockito.verify(log).warn("serviceName is required Skipping. Set sanshain.strict=true to fail in this case.");
    }
}
