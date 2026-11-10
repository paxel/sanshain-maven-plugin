package com.sanshain.maven;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The annotations of a build: the {@code annotations} entries of sanshain.yaml with their
 * {@code {{NAME}}} placeholders filled in by the build. Sanshain shows them with the version
 * ({@code scope: version}) or with the system ({@code scope: system}); it never interprets them.
 *
 * <p>A placeholder is {@code {{NAME}}} with NAME of letters, digits and {@code _} only; anything
 * else between double braces is literal text. Its value is the Maven property {@code NAME}
 * ({@code -DNAME=value}) first, else the environment variable {@code SANSHAIN_SET_NAME}; it
 * counts as filled only when that value is not empty. An entry with any unfilled placeholder is
 * left out, with a warning naming the entry and the placeholder; the others are still sent.
 *
 * <p>Where they travel: a provide sends every entry, every other call naming the system
 * (require, bundle, catalog push) only the {@code scope: system} ones. A retire sends none.
 */
public final class SanshainAnnotations {

    /** The scope of an entry stored with the provided version. */
    public static final String VERSION = "version";
    /** The scope of an entry stored with the system. */
    public static final String SYSTEM = "system";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    private static final SanshainAnnotations NONE =
            new SanshainAnnotations(Collections.emptyList(), Collections.emptyList());

    private final List<Map<String, String>> entries;
    private final List<String> warnings;

    private SanshainAnnotations(List<Map<String, String>> entries, List<String> warnings) {
        this.entries = Collections.unmodifiableList(entries);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /**
     * No annotations at all.
     * @return the empty annotations
     */
    public static SanshainAnnotations none() {
        return NONE;
    }

    /**
     * Fills the declared entries.
     *
     * @param declared the {@code annotations} entries of sanshain.yaml, or null
     * @param flags    the value of a Maven property by name, or null if it is not set
     * @param env      the environment variables of the build
     * @return the filled entries in file order, and a warning for every entry left out
     */
    public static SanshainAnnotations resolve(List<SanshainConfig.AnnotationConfig> declared,
                                              Function<String, String> flags, Map<String, String> env) {
        if (declared == null || declared.isEmpty()) {
            return NONE;
        }
        List<Map<String, String>> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (SanshainConfig.AnnotationConfig entry : declared) {
            Fill fill = fill(entry.getValue(), flags, env);
            if (fill.missing != null) {
                warnings.add("annotation '" + entry.getName() + "' left out: {{" + fill.missing + "}} is not set (-D"
                        + fill.missing + "=… or SANSHAIN_SET_" + fill.missing + ")");
                continue;
            }
            Map<String, String> wire = new LinkedHashMap<>();
            wire.put("scope", entry.getScope());
            wire.put("name", entry.getName());
            wire.put("value", fill.value);
            entries.add(wire);
        }
        return new SanshainAnnotations(entries, warnings);
    }

    /**
     * Fills the placeholders of one value. Every {@code {{NAME}}} takes the flag {@code NAME},
     * else the environment variable {@code SANSHAIN_SET_NAME}; an empty value is not a value.
     *
     * @param template the value as written in sanshain.yaml
     * @param flags    the value of a Maven property by name, or null if it is not set
     * @param env      the environment variables of the build
     * @return the filled value, or the first placeholder nobody filled
     */
    static Fill fill(String template, Function<String, String> flags, Map<String, String> env) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = flags.apply(name);
            if (value == null) {
                value = env.get("SANSHAIN_SET_" + name);
            }
            if (value == null || value.isEmpty()) {
                return new Fill(null, name);
            }
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(filled);
        return new Fill(filled.toString(), null);
    }

    /** The outcome of {@link #fill(String, Function, Map)}: a value, or the placeholder missing one. */
    static final class Fill {
        final String value;
        final String missing;

        Fill(String value, String missing) {
            this.value = value;
            this.missing = missing;
        }
    }

    /**
     * The entries a provide sends: all of them, both scopes, as {@code {scope, name, value}}.
     * @return the entries in file order; empty when there are none
     */
    public List<Map<String, String>> forProvide() {
        return entries;
    }

    /**
     * The entries every other call naming the system sends: {@code scope: system} only, since
     * Sanshain refuses a version entry anywhere but on a provide.
     * @return the system entries in file order; empty when there are none
     */
    public List<Map<String, String>> forSystem() {
        List<Map<String, String>> system = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            if (SYSTEM.equals(entry.get("scope"))) {
                system.add(entry);
            }
        }
        return system;
    }

    /**
     * One warning for every entry left out because a placeholder was not filled.
     * @return the warnings, in file order
     */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "annotations: " + entries;
    }
}
