package com.sanshain.maven;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.maven.plugin.MojoExecutionException;
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

    @Test
    public void testRejectedFileFailsWithMetadataNameHint() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"error\":\"no Component named orders-service\"}")));
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, createMojo(YAML)::execute);
        assertEquals("Sanshain rejected " + tempDir.resolve("catalog-info.yaml").toFile().getAbsolutePath()
                + ": no Component named orders-service. "
                + "The Component's metadata.name must equal serviceName 'orders-service'.", ex.getMessage());
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
    public void testBestEffortTurnsRejectionIntoWarning() throws Exception {
        wireMock.stubFor(put(urlEqualTo("/catalog/orders-service"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad\"}")));
        CatalogMojo mojo = createMojo(YAML);
        setField(mojo, "bestEffort", true);
        mojo.execute();
        Mockito.verify(log).warn("Sanshain goal failed (best effort): Sanshain rejected "
                + tempDir.resolve("catalog-info.yaml").toFile().getAbsolutePath() + ": bad. "
                + "The Component's metadata.name must equal serviceName 'orders-service'.");
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
}
