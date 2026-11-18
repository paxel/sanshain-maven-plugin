package com.sanshain.maven;

import com.code_intelligence.jazzer.junit.FuzzTest;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Fills the placeholders of annotation values: any value, any property and any environment.
 * Every value is either filled or names the placeholder nobody filled, never both and never
 * neither.
 *
 * <p>The first line of an input holds the annotation values, separated by tabs. Every further
 * line is a {@code NAME=value} pair, by turns a Maven property and an environment variable.</p>
 *
 * <p>The inputs in {@code src/test/resources/com/sanshain/maven/AnnotationsFuzzTestInputs} run
 * with every test run; {@code JAZZER_FUZZ=1 mvn test -Dtest=AnnotationsFuzzTest} fuzzes.</p>
 */
class AnnotationsFuzzTest {

    @FuzzTest
    void annotations(byte[] data) {
        String[] lines = new String(data, StandardCharsets.UTF_8).split("\n", -1);
        Map<String, String> flags = new HashMap<>();
        Map<String, String> env = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int equals = lines[i].indexOf('=');
            if (equals < 0) {
                continue;
            }
            String name = lines[i].substring(0, equals);
            String value = lines[i].substring(equals + 1);
            if (i % 2 == 1) {
                flags.put(name, value);
            } else {
                env.put("SANSHAIN_SET_" + name, value);
            }
        }
        for (String template : lines[0].split("\t", -1)) {
            SanshainAnnotations.Fill fill = SanshainAnnotations.fill(template, flags::get, env);
            assertNotEquals(fill.value == null, fill.missing == null,
                    "a value or a missing placeholder for: " + template);
            if (!template.contains("{{")) {
                assertEquals(template, fill.value);
            }
        }
    }
}
