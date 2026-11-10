package com.sanshain.maven;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class SanshainAnnotationsTest {

    private static final Map<String, String> NO_ENV = Collections.emptyMap();
    private static final Function<String, String> NO_FLAGS = name -> null;

    private static Function<String, String> flags(String... nameValue) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            flags.put(nameValue[i], nameValue[i + 1]);
        }
        return flags::get;
    }

    private static Map<String, String> env(String name, String value) {
        return Collections.singletonMap(name, value);
    }

    private static SanshainConfig.AnnotationConfig entry(String scope, String name, String value) {
        return new SanshainConfig.AnnotationConfig(scope, name, value);
    }

    // --- the fill function ---

    @Test
    public void testFlagAndEnvFillDifferentPlaceholders() {
        SanshainAnnotations.Fill fill = SanshainAnnotations.fill("x-{{A}}-{{B}}", flags("A", "1"), env("SANSHAIN_SET_B", "2"));
        assertEquals("x-1-2", fill.value);
        assertNull(fill.missing);
    }

    @Test
    public void testFlagWinsOverEnv() {
        assertEquals("f", SanshainAnnotations.fill("{{A}}", flags("A", "f"), env("SANSHAIN_SET_A", "e")).value);
    }

    @Test
    public void testRepeatedPlaceholderFillsEveryTime() {
        assertEquals("7/7", SanshainAnnotations.fill("{{A}}/{{A}}", flags("A", "7"), NO_ENV).value);
    }

    @Test
    public void testUnsetPlaceholderIsMissing() {
        SanshainAnnotations.Fill fill = SanshainAnnotations.fill("{{MISSING}}", NO_FLAGS, NO_ENV);
        assertNull(fill.value);
        assertEquals("MISSING", fill.missing);
    }

    @Test
    public void testEmptyFlagIsUnfilled() {
        SanshainAnnotations.Fill fill = SanshainAnnotations.fill("{{A}}", flags("A", ""), NO_ENV);
        assertNull(fill.value);
        assertEquals("A", fill.missing);
    }

    @Test
    public void testEmptyEnvIsUnfilled() {
        assertEquals("A", SanshainAnnotations.fill("{{A}}", NO_FLAGS, env("SANSHAIN_SET_A", "")).missing);
    }

    @Test
    public void testEnvNameIsCaseExact() {
        assertEquals("a", SanshainAnnotations.fill("{{a}}", NO_FLAGS, env("SANSHAIN_SET_A", "x")).missing);
    }

    @Test
    public void testPlainValueIsUnchanged() {
        assertEquals("plain value", SanshainAnnotations.fill("plain value", NO_FLAGS, NO_ENV).value);
    }

    @Test
    public void testNonPlaceholderBracesAreLiteral() {
        for (String literal : Arrays.asList("{{ A }}", "{{a-b}}", "{{}}")) {
            SanshainAnnotations.Fill fill = SanshainAnnotations.fill(literal, flags("A", "1", "a-b", "2"), NO_ENV);
            assertEquals(literal, fill.value);
            assertNull(fill.missing);
        }
    }

    @Test
    public void testValueWithEqualsSignStaysWhole() {
        // -DA=x=y: Maven splits at the first '=', so the property A is "x=y".
        assertEquals("x=y", SanshainAnnotations.fill("{{A}}", flags("A", "x=y"), NO_ENV).value);
    }

    @Test
    public void testReplacementCharactersAreTakenLiterally() {
        assertEquals("a$1\\b", SanshainAnnotations.fill("{{A}}", flags("A", "a$1\\b"), NO_ENV).value);
    }

    // --- resolving the entries ---

    @Test
    public void testUnfilledEntryIsLeftOutWithWarningAndOthersAreKept() {
        SanshainAnnotations annotations = SanshainAnnotations.resolve(Arrays.asList(
                entry("version", "requirements", "http://solar.system/id-{{AREA}}/requirements/{{MISSING}}"),
                entry("system", "jenkins", "https://jenkins.corp/job/payments")),
                flags("AREA", "7"), NO_ENV);

        assertEquals(Collections.singletonList(
                "annotation 'requirements' left out: {{MISSING}} is not set (-DMISSING=… or SANSHAIN_SET_MISSING)"),
                annotations.getWarnings());
        List<Map<String, String>> sent = annotations.forProvide();
        assertEquals(1, sent.size());
        assertEquals("system", sent.get(0).get("scope"));
        assertEquals("jenkins", sent.get(0).get("name"));
        assertEquals("https://jenkins.corp/job/payments", sent.get(0).get("value"));
    }

    @Test
    public void testProvideSendsBothScopesAndOthersOnlySystem() {
        SanshainAnnotations annotations = SanshainAnnotations.resolve(Arrays.asList(
                entry("version", "requirements", "http://solar.system/id-{{AREA}}/requirements/{{ID}}"),
                entry("system", "jenkins", "https://jenkins.corp/job/payments")),
                flags("AREA", "7"), env("SANSHAIN_SET_ID", "42"));

        assertTrue(annotations.getWarnings().isEmpty());
        assertEquals(2, annotations.forProvide().size());
        assertEquals("http://solar.system/id-7/requirements/42", annotations.forProvide().get(0).get("value"));
        assertEquals("version", annotations.forProvide().get(0).get("scope"));
        assertEquals(1, annotations.forSystem().size());
        assertEquals("jenkins", annotations.forSystem().get(0).get("name"));
    }

    @Test
    public void testNoEntriesIsNone() {
        assertSame(SanshainAnnotations.none(), SanshainAnnotations.resolve(null, NO_FLAGS, NO_ENV));
        assertSame(SanshainAnnotations.none(), SanshainAnnotations.resolve(Collections.emptyList(), NO_FLAGS, NO_ENV));
        assertTrue(SanshainAnnotations.none().forProvide().isEmpty());
        assertTrue(SanshainAnnotations.none().forSystem().isEmpty());
    }
}
