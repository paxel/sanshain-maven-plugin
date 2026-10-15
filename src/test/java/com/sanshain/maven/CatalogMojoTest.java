package com.sanshain.maven;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class CatalogMojoTest {

    private static final String CATALOG = "apiVersion: backstage.io/v1alpha1\n"
            + "kind: Component\n"
            + "metadata:\n"
            + "  name: orders-service\n"
            + "spec:\n"
            + "  dependsOn:\n"
            + "    - resource:default/orders-db\n";

    private static final String RECORDED = "{\"system\":\"orders-service\",\"trunk\":%s,"
            + "\"resources\":[\"resource:default/orders-db\",\"resource:default/orders-queue\"]}";

    @TempDir
    Path tempDir;

    private WireMockServer wireMock;
    private String baseUrl;
    private Log log;

    @BeforeEach
    public void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();
        log = Mockito.mock(Log.class);
    }

    @AfterEach
    public void tearDown() {
        wireMock.stop();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private CatalogMojo createMojo(Path configPath, String yaml) throws Exception {
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, yaml);

        CatalogMojo mojo = new CatalogMojo();
        setField(mojo, "baseDir", tempDir.toFile());
        setField(mojo, "configFile", configPath.toFile());
        setField(mojo, "sanshainUrl", baseUrl);
        setField(mojo, "compression", false);
        setField(mojo, "serverId", "sanshain");
        setField(mojo, "trunk", false);
        mojo.setLog(log);
        return mojo;
    }

    private CatalogMojo createMojo(String yaml) throws Exception {
        Files.writeString(tempDir.resolve("catalog-info.yaml"), CATALOG);
        return createMojo(tempDir.resolve("sanshain.yaml"), yaml);
    }

    private static final String YAML = "serviceName: orders-service\ncatalogInfo: catalog-info.yaml\n";

    private void stubRecorded(boolean trunk) {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service"))
                .willReturn(aResponse().withStatus(200).withBody(String.format(RECORDED, trunk))));
    }

    private String sentBody() {
        return wireMock.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/catalog/"))
                .findFirst().orElseThrow().getRequest().getBodyAsString();
    }

    @Test
    public void testTrunkBuildSendsTrunkTrue() throws Exception {
        stubRecorded(true);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "trunk", true);
        mojo.execute();

        wireMock.verify(putRequestedFor(urlEqualTo("/catalog/orders-service"))
                .withRequestBody(matchingJsonPath("$.catalog_info_yaml", equalTo(CATALOG)))
                .withRequestBody(matchingJsonPath("$.trunk", equalTo("true"))));
        assertFalse(sentBody().contains("\"tag\""), sentBody());
    }

    @Test
    public void testPlainBuildOmitsTrunk() throws Exception {
        stubRecorded(false);
        createMojo(YAML).execute();

        String body = sentBody();
        assertTrue(body.contains("\"catalog_info_yaml\""), body);
        assertFalse(body.contains("\"trunk\""), body);
        assertFalse(body.contains("\"tag\""), body);
    }

    @Test
    public void testTagBuildSendsNeitherTrunkNorTag() throws Exception {
        stubRecorded(false);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "trunk", null);
        setField(mojo, "tag", "release-7");
        mojo.execute();

        String body = sentBody();
        assertFalse(body.contains("\"trunk\""), body);
        assertFalse(body.contains("\"tag\""), body);
        assertFalse(body.contains("release-7"), body);
    }

    @Test
    public void testRecordedOutput() throws Exception {
        stubRecorded(true);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "trunk", true);
        mojo.execute();

        InOrder inOrder = Mockito.inOrder(log);
        inOrder.verify(log).info("Catalog recorded for orders-service (main+dev): 2 resources");
        inOrder.verify(log).info("  resource:default/orders-db");
        inOrder.verify(log).info("  resource:default/orders-queue");
    }

    @Test
    public void testRecordedOutputForDevOnly() throws Exception {
        stubRecorded(false);
        createMojo(YAML).execute();
        Mockito.verify(log).info("Catalog recorded for orders-service (dev): 2 resources");
    }

    @Test
    public void testServiceNameIsUrlEncodedAsPathSegment() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders%20service"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"system\":\"orders service\",\"trunk\":false,\"resources\":[]}")));
        createMojo("serviceName: orders service\ncatalogInfo: catalog-info.yaml\n").execute();
        wireMock.verify(putRequestedFor(urlEqualTo("/catalog/orders%20service")));
    }

    /** A 400 can only come from a 2.4.0 pre-release service now; it gets the normal mapping. */
    @Test
    public void testBadRequestUsesNormalMapping() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":\"no Component named orders-service\"}")));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, createMojo(YAML)::execute);
        assertEquals("Unexpected response 400: {\"error\":\"no Component named orders-service\"}", ex.getMessage());
    }

    private static final String NOT_RECORDED =
            "{\"system\":\"orders-service\",\"trunk\":false,\"resources\":[],\"recorded\":false}";

    private static final String NO_COMPONENT = "no Component with metadata.name 'orders-service'";

    private void stubNotRecorded(String... warnings) {
        com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response =
                aResponse().withStatus(200).withBody(NOT_RECORDED);
        if (warnings.length > 0) {
            response = response.withHeader("Sanshain-Warning", warnings);
        }
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service")).willReturn(response));
    }

    @Test
    public void testNotRecordedPrintsTheWarningAndSucceeds() throws Exception {
        stubNotRecorded(NO_COMPONENT);
        createMojo(YAML).execute();
        Mockito.verify(log).warn(NO_COMPONENT);
        Mockito.verify(log, Mockito.never()).info(Mockito.startsWith("Catalog recorded"));
    }

    @Test
    public void testNotRecordedWithoutHeaderWarnsDidNotRecord() throws Exception {
        stubNotRecorded();
        createMojo(YAML).execute();
        Mockito.verify(log).warn("Sanshain did not record "
                + tempDir.resolve("catalog-info.yaml").toFile().getAbsolutePath());
        Mockito.verify(log, Mockito.never()).info(Mockito.startsWith("Catalog recorded"));
    }

    @Test
    public void testNotRecordedUnderStrictFails() throws Exception {
        stubNotRecorded(NO_COMPONENT);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "strict", true);
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("strict: Sanshain warned: " + NO_COMPONENT, ex.getMessage());
        Mockito.verify(log).warn(NO_COMPONENT);
    }

    @Test
    public void testNotRecordedWithoutHeaderUnderStrictFailsEvenWithBestEffort() throws Exception {
        stubNotRecorded();
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("strict: Sanshain warned: Sanshain did not record "
                + tempDir.resolve("catalog-info.yaml").toFile().getAbsolutePath(), ex.getMessage());
    }

    /** An older 2.4.0 pre-release service sends no {@code recorded}: that means recorded. */
    @Test
    public void testMissingRecordedCountsAsRecorded() throws Exception {
        stubRecorded(false);
        createMojo(YAML).execute();
        Mockito.verify(log).info("Catalog recorded for orders-service (dev): 2 resources");
        Mockito.verify(log, Mockito.never()).warn(Mockito.startsWith("Sanshain did not record"));
    }

    @Test
    public void testRecordedTrueWithWarningPrintsBoth() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service")).willReturn(aResponse().withStatus(200)
                .withHeader("Sanshain-Warning", "first, with a comma", "second")
                .withBody("{\"system\":\"orders-service\",\"trunk\":false,\"resources\":[],\"recorded\":true}")));
        createMojo(YAML).execute();
        InOrder order = Mockito.inOrder(log);
        order.verify(log).warn("first, with a comma");
        order.verify(log).warn("second");
        Mockito.verify(log).info("Catalog recorded for orders-service (dev): 0 resources");
    }

    /** A failed call that carries a warning under strict: the error is printed, then strict fails. */
    @Test
    public void testFailedCallWithWarningUnderStrictPrintsErrorThenFails() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service")).willReturn(aResponse().withStatus(503)
                .withHeader("Sanshain-Warning", "degraded").withBody("down")));
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "strict", true);
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("strict: Sanshain warned: degraded", ex.getMessage());
        InOrder order = Mockito.inOrder(log);
        order.verify(log).warn("degraded");
        order.verify(log).error("Unexpected response 503: down");
    }

    /** Without strict, the failed call's own error wins and the warning is still printed. */
    @Test
    public void testFailedCallWithWarningWithoutStrictKeepsTheError() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service")).willReturn(aResponse().withStatus(503)
                .withHeader("Sanshain-Warning", "degraded").withBody("down")));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, createMojo(YAML)::execute);
        assertEquals("Unexpected response 503: down", ex.getMessage());
        Mockito.verify(log).warn("degraded");
    }

    @Test
    public void testLocalTrunkAndTagWarnsAndSendsNeither() throws Exception {
        stubRecorded(false);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "trunk", true);
        setField(mojo, "tag", "release-7");
        mojo.execute();
        Mockito.verify(log).warn("trunk and tag are mutually exclusive - this build is recorded in neither");
        assertFalse(sentBody().contains("\"trunk\""), sentBody());
        assertFalse(sentBody().contains("\"tag\""), sentBody());
    }

    @Test
    public void testLocalTrunkAndTagUnderStrictFailsBeforeAnyRequest() throws Exception {
        stubRecorded(false);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "trunk", true);
        setField(mojo, "tag", "release-7");
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);
        MojoFailureException ex = assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("strict: trunk and tag are mutually exclusive - this build is recorded in neither",
                ex.getMessage());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testNotFoundNeedsService240WithServerVersion() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse().withStatus(200).withBody("{\"version\":\"2.3.1\"}")));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, createMojo(YAML)::execute);
        assertEquals("sanshain catalog needs Sanshain Service 2.4.0 or later (server is 2.3.1)", ex.getMessage());
    }

    @Test
    public void testNotFoundNeedsService240WithUnknownVersion() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(get(urlEqualTo("/version")).willReturn(aResponse().withStatus(404)));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, createMojo(YAML)::execute);
        assertEquals("sanshain catalog needs Sanshain Service 2.4.0 or later (server version unknown)", ex.getMessage());
    }

    @Test
    public void testServerErrorUsesNormalMapping() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service"))
                .willReturn(aResponse().withStatus(503).withBody("down")));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, createMojo(YAML)::execute);
        assertEquals("Unexpected response 503: down", ex.getMessage());
    }

    @Test
    public void testNoCatalogInfoWarnsAndSkips() throws Exception {
        createMojo("serviceName: orders-service\n").execute();
        Mockito.verify(log).warn("No catalogInfo in sanshain.yaml - skipping catalog.");
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testNoCatalogInfoUnderStrictFailsEvenWithBestEffort() throws Exception {
        CatalogMojo mojo = createMojo("serviceName: orders-service\n");
        setField(mojo, "strict", true);
        setField(mojo, "bestEffort", true);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("strict: sanshain.yaml has no catalogInfo", ex.getMessage());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testStrictDoesNotRequireProvidesOrRequires() throws Exception {
        stubRecorded(false);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "strict", true);
        mojo.execute();
        wireMock.verify(1, putRequestedFor(urlEqualTo("/catalog/orders-service")));
    }

    @Test
    public void testBestEffortTurnsFailureIntoWarning() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad\"}")));
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "bestEffort", true);
        mojo.execute();
        Mockito.verify(log).warn("Sanshain goal failed (best effort): Unexpected response 400: {\"error\":\"bad\"}");
    }

    @Test
    public void testMissingFileFailsNamingResolvedPath() throws Exception {
        Path configPath = tempDir.resolve("conf/sanshain.yaml");
        CatalogMojo mojo = createMojo(configPath, YAML);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        String expected = tempDir.resolve("conf/catalog-info.yaml").toFile().getAbsolutePath();
        assertTrue(ex.getMessage().contains(expected), ex.getMessage());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testMissingFileUnderBestEffortWarns() throws Exception {
        CatalogMojo mojo = createMojo(tempDir.resolve("conf/sanshain.yaml"), YAML);
        setField(mojo, "bestEffort", true);
        mojo.execute();
        Mockito.verify(log).warn(Mockito.startsWith("Sanshain goal failed (best effort): Cannot read catalogInfo file "));
    }

    @Test
    public void testRelativeCatalogInfoResolvesNextToConfigInAnotherFolder() throws Exception {
        stubRecorded(false);
        Path confDir = Files.createDirectories(tempDir.resolve("conf"));
        Files.writeString(confDir.resolve("catalog-info.yaml"), CATALOG);
        Files.writeString(tempDir.resolve("catalog-info.yaml"), "wrong file");
        createMojo(confDir.resolve("sanshain.yaml"), YAML).execute();

        wireMock.verify(putRequestedFor(urlEqualTo("/catalog/orders-service"))
                .withRequestBody(matchingJsonPath("$.catalog_info_yaml", equalTo(CATALOG))));
    }

    @Test
    public void testSkipDoesNothing() throws Exception {
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "skip", true);
        mojo.execute();
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testNoCatalogInfoFailsWhenYamlStrict() throws Exception {
        CatalogMojo mojo = createMojo("serviceName: orders-service\nstrict: true\n");
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("strict: sanshain.yaml has no catalogInfo", ex.getMessage());
    }

    @Test
    public void testDryRunChecksFileLocallyAndSendsNothing() throws Exception {
        stubRecorded(false);
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "dryRun", true);
        mojo.execute();

        Mockito.verify(log).info("Dry run - catalog not pushed: "
                + tempDir.resolve("catalog-info.yaml").toFile().getAbsolutePath());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testDryRunStillFailsOnMissingFile() throws Exception {
        CatalogMojo mojo = createMojo(tempDir.resolve("conf/sanshain.yaml"), YAML);
        setField(mojo, "dryRun", true);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains(tempDir.resolve("conf/catalog-info.yaml").toFile().getAbsolutePath()), ex.getMessage());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testDryRunMissingFileWarnsUnderBestEffort() throws Exception {
        CatalogMojo mojo = createMojo(tempDir.resolve("conf/sanshain.yaml"), YAML);
        setField(mojo, "dryRun", true);
        setField(mojo, "bestEffort", true);
        mojo.execute();
        Mockito.verify(log).warn(Mockito.startsWith("Sanshain goal failed (best effort): Cannot read catalogInfo file "));
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }

    @Test
    public void testDryRunWithoutCatalogInfoStillWarnsOrStrictFails() throws Exception {
        CatalogMojo warn = createMojo("serviceName: orders-service\n");
        setField(warn, "dryRun", true);
        warn.execute();
        Mockito.verify(log).warn("No catalogInfo in sanshain.yaml - skipping catalog.");

        CatalogMojo strict = createMojo("serviceName: orders-service\n");
        setField(strict, "dryRun", true);
        setField(strict, "strict", true);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, strict::execute);
        assertEquals("strict: sanshain.yaml has no catalogInfo", ex.getMessage());
        assertTrue(wireMock.getAllServeEvents().isEmpty());
    }
}
