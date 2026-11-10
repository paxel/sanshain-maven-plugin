package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class SpecCombinerTest {

    @TempDir
    Path tempDir;

    private SpecCombiner combiner;

    @BeforeEach
    public void setUp() {
        combiner = new SpecCombiner();
    }

    @Test
    public void testYamlSimpleRef() throws IOException, MojoExecutionException {
        String mainYaml = "openapi: 3.0.0\n" +
                "info:\n" +
                "  title: Test API\n" +
                "  version: 1.0.0\n" +
                "paths:\n" +
                "  /users:\n" +
                "    get:\n" +
                "      responses:\n" +
                "        '200':\n" +
                "          description: OK\n" +
                "          content:\n" +
                "            application/json:\n" +
                "              schema:\n" +
                "                $ref: './dto.yaml'\n";
        String dtoYaml = "type: object\n" +
                "properties:\n" +
                "  name:\n" +
                "    type: string\n";

        Path mainPath = tempDir.resolve("main.yaml");
        Path dtoPath = tempDir.resolve("dto.yaml");

        Files.writeString(mainPath, mainYaml);
        Files.writeString(dtoPath, dtoYaml);

        String result = combiner.combine(mainPath.toFile(), "openapi");
        System.out.println("DEBUG RESULT: " + result);
        
        // Assert that $ref is gone and replaced by dto contents
        assertTrue(result.contains("name:"));
        assertTrue(result.contains("string"));
        assertFalse(result.contains("$ref: './dto.yaml'"));
        assertFalse(result.contains("$ref: \"./dto.yaml\""));
    }

    @Test
    public void testYamlNestedRef() throws IOException, MojoExecutionException {
        String mainYaml = "schema:\n" +
                "  $ref: 'sub/dto.yaml'\n";
        String dtoYaml = "type: object\n" +
                "properties:\n" +
                "  address:\n" +
                "    $ref: '../common/address.yaml'\n";
        String addressYaml = "type: object\n" +
                "properties:\n" +
                "  city:\n" +
                "    type: string\n";

        Path mainPath = tempDir.resolve("main.yaml");
        Path subDir = Files.createDirectory(tempDir.resolve("sub"));
        Path commonDir = Files.createDirectory(tempDir.resolve("common"));

        Path dtoPath = subDir.resolve("dto.yaml");
        Path addressPath = commonDir.resolve("address.yaml");

        Files.writeString(mainPath, mainYaml);
        Files.writeString(dtoPath, dtoYaml);
        Files.writeString(addressPath, addressYaml);

        String result = combiner.combine(mainPath.toFile(), "openapi");

        assertTrue(result.contains("city:"));
        assertTrue(result.contains("address:"));
        assertFalse(result.contains("sub/dto.yaml"));
        assertFalse(result.contains("address.yaml"));
    }

    @Test
    public void testYamlFragmentResolution() throws IOException, MojoExecutionException {
        String mainYaml = "schema:\n" +
                "  $ref: './components.yaml#/components/schemas/User'\n";
        String componentsYaml = "components:\n" +
                "  schemas:\n" +
                "    User:\n" +
                "      type: object\n" +
                "      properties:\n" +
                "        username:\n" +
                "          type: string\n" +
                "    Address:\n" +
                "      type: object\n";

        Path mainPath = tempDir.resolve("main.yaml");
        Path compPath = tempDir.resolve("components.yaml");

        Files.writeString(mainPath, mainYaml);
        Files.writeString(compPath, componentsYaml);

        String result = combiner.combine(mainPath.toFile(), "openapi");

        assertTrue(result.contains("username:"));
        assertFalse(result.contains("User:"));
        assertFalse(result.contains("Address:"));
    }

    @Test
    public void testYamlCircularDependency() throws IOException {
        String fileAYaml = "schema:\n" +
                "  $ref: './fileB.yaml'\n";
        String fileBYaml = "schema:\n" +
                "  $ref: './fileA.yaml'\n";

        Path pathA = tempDir.resolve("fileA.yaml");
        Path pathB = tempDir.resolve("fileB.yaml");

        Files.writeString(pathA, fileAYaml);
        Files.writeString(pathB, fileBYaml);

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                combiner.combine(pathA.toFile(), "openapi"));
        assertTrue(ex.getMessage().contains("Circular dependency detected"));
    }

    @Test
    public void testProtoSimpleImport() throws IOException, MojoExecutionException {
        String mainProto = "syntax = \"proto3\";\n" +
                "package main;\n" +
                "import \"sub/dto.proto\";\n" +
                "import \"google/protobuf/timestamp.proto\";\n" +
                "message Main {\n" +
                "  string id = 1;\n" +
                "}\n";
        String dtoProto = "syntax = \"proto3\";\n" +
                "package sub;\n" +
                "message Dto {\n" +
                "  string val = 1;\n" +
                "}\n";

        Path mainPath = tempDir.resolve("main.proto");
        Path subDir = Files.createDirectory(tempDir.resolve("sub"));
        Path dtoPath = subDir.resolve("dto.proto");

        Files.writeString(mainPath, mainProto);
        Files.writeString(dtoPath, dtoProto);

        String result = combiner.combine(mainPath.toFile(), "proto");

        // Should inline Dto but strip syntax/package from dto.proto
        assertTrue(result.contains("message Dto {"));
        assertTrue(result.contains("message Main {"));
        assertTrue(result.contains("import \"google/protobuf/timestamp.proto\";"));
        assertFalse(result.contains("import \"sub/dto.proto\";"));
        
        // Count total occurrences of syntax / package
        int syntaxCount = countOccurrences(result, "syntax =");
        int packageCount = countOccurrences(result, "package ");
        
        assertEquals(1, syntaxCount, "Only root syntax should be kept");
        assertEquals(1, packageCount, "Only root package should be kept");
    }

    @Test
    public void testProtoCircularImport() throws IOException {
        String protoA = "syntax = \"proto3\";\n" +
                "import \"protoB.proto\";\n";
        String protoB = "syntax = \"proto3\";\n" +
                "import \"protoA.proto\";\n";

        Path pathA = tempDir.resolve("protoA.proto");
        Path pathB = tempDir.resolve("protoB.proto");

        Files.writeString(pathA, protoA);
        Files.writeString(pathB, protoB);

        MojoExecutionException ex = assertThrows(MojoExecutionException.class, () ->
                combiner.combine(pathA.toFile(), "proto"));
        assertTrue(ex.getMessage().contains("Circular dependency detected"));
    }

    /**
     * The package line pattern takes a {@code package} statement with any spacing, an optional
     * trailing comment, and nothing else. The lines are the ones the earlier, backtracking pattern
     * {@code ^\\s*package\\s+[^;]+\\s*;\\s*(?://.*)?$} decided the same way, so the simpler
     * pattern changed no answer.
     */
    @Test
    public void testPackagePatternTakesPackageLinesOnly() {
        Pattern pattern = combiner.packagePattern;
        List<String> packageLines = List.of(
                "package foo.bar;",
                "package foo;",
                "  package foo.bar;",
                "\tpackage foo.bar;",
                "package foo.bar ;",
                "package   foo.bar   ;   ",
                "package foo.bar; // the package",
                "package foo.bar;// comment",
                "package a . b;",
                "package   ;",
                "package\tfoo;");
        List<String> otherLines = List.of(
                "package foo",
                "package;",
                "package ;x",
                "package foo; bar",
                "packages foo;",
                "packagefoo;",
                "// package foo;",
                "import \"other.proto\";",
                "syntax = \"proto3\";",
                "message Foo { string package = 1; }",
                "",
                "   ");
        for (String line : packageLines) {
            assertTrue(pattern.matcher(line).matches(), line);
        }
        for (String line : otherLines) {
            assertFalse(pattern.matcher(line).matches(), line);
        }
    }

    @Test
    public void testPackagePatternRejectsALongLineWithoutSemicolonQuickly() {
        String line = "package" + " ".repeat(20_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertFalse(combiner.packagePattern.matcher(line).matches()));
    }

    private int countOccurrences(String str, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }
}