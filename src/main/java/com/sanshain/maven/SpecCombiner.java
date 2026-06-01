package com.sanshain.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processor domain object that combines multi-file API specifications (OpenAPI, AsyncAPI, Protobuf)
 * into a single unified specification.
 */
public class SpecCombiner {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Pattern importPattern = Pattern.compile("^\\s*import\\s+(?:public\\s+|weak\\s+)?[\"']([^\"']+)[\"']\\s*;\\s*(?://.*)?$");
    private final Pattern syntaxPattern = Pattern.compile("^\\s*syntax\\s*=\\s*[\"'][^\"']+[\"']\\s*;\\s*(?://.*)?$");
    private final Pattern packagePattern = Pattern.compile("^\\s*package\\s+[^;]+\\s*;\\s*(?://.*)?$");

    public SpecCombiner() {
    }

    /**
     * Combines the given specification file recursively based on its API type.
     *
     * @param file    the specification file
     * @param apiType the API type ("openapi", "asyncapi", "proto", etc.)
     * @return the combined specification content as a String
     * @throws MojoExecutionException if combining fails or circular dependency is detected
     */
    public String combine(File file, String apiType) throws MojoExecutionException {
        if (file == null) {
            throw new MojoExecutionException("Specification file cannot be null.");
        }
        if (!file.exists()) {
            throw new MojoExecutionException("Specification file does not exist: " + file.getAbsolutePath());
        }

        if (apiType == null) {
            apiType = "openapi";
        }

        String type = apiType.toLowerCase();
        if (type.equals("proto") || type.equals("grpc")) {
            return combineProtobuf(file);
        } else if (type.equals("openapi") || type.equals("asyncapi")) {
            return combineYamlJson(file);
        } else {
            throw new MojoExecutionException("Unsupported apiType for combining: " + apiType);
        }
    }

    private String combineYamlJson(File file) throws MojoExecutionException {
        try {
            File canonicalFile = file.getCanonicalFile();
            Set<String> visited = new LinkedHashSet<>();
            visited.add(canonicalFile.getCanonicalPath());
            
            JsonNode resolvedRoot = parseAndResolve(canonicalFile, visited);
            
            ObjectMapper mapper = getMapperForFile(canonicalFile);
            return mapper.writeValueAsString(resolvedRoot);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to combine YAML/JSON file: " + file.getAbsolutePath(), e);
        }
    }

    private JsonNode parseAndResolve(File file, Set<String> visitedPaths) throws MojoExecutionException {
        ObjectMapper mapper = getMapperForFile(file);
        JsonNode root;
        try {
            root = mapper.readTree(file);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to parse " + file.getAbsolutePath(), e);
        }
        return resolveReferences(root, file, visitedPaths);
    }

    private JsonNode resolveReferences(JsonNode node, File currentFile, Set<String> visitedPaths) throws MojoExecutionException {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode objNode = (ObjectNode) node;
            if (objNode.has("$ref")) {
                String refVal = objNode.get("$ref").asText();
                if (!refVal.startsWith("#") && !refVal.startsWith("http://") && !refVal.startsWith("https://")) {
                    String filePath;
                    String fragment = null;
                    int hashIdx = refVal.indexOf('#');
                    if (hashIdx >= 0) {
                        filePath = refVal.substring(0, hashIdx);
                        fragment = refVal.substring(hashIdx + 1);
                    } else {
                        filePath = refVal;
                    }

                    if (!filePath.isEmpty()) {
                        File targetFile;
                        try {
                            targetFile = new File(currentFile.getParentFile(), filePath).getCanonicalFile();
                        } catch (IOException e) {
                            throw new MojoExecutionException("Failed to get canonical path for: " + filePath, e);
                        }

                        if (!targetFile.exists()) {
                            throw new MojoExecutionException("Referenced file does not exist: " + targetFile.getAbsolutePath());
                        }

                        String canonicalPath;
                        try {
                            canonicalPath = targetFile.getCanonicalPath();
                        } catch (IOException e) {
                            throw new MojoExecutionException("Failed to get canonical path", e);
                        }

                        if (visitedPaths.contains(canonicalPath)) {
                            throw new MojoExecutionException("Circular dependency detected: " + String.join(" -> ", visitedPaths) + " -> " + canonicalPath);
                        }

                        Set<String> nextVisited = new LinkedHashSet<>(visitedPaths);
                        nextVisited.add(canonicalPath);

                        JsonNode resolvedFileRoot = parseAndResolve(targetFile, nextVisited);

                        JsonNode replacementNode = resolvedFileRoot;
                        if (fragment != null && !fragment.isEmpty()) {
                            if (fragment.startsWith("#")) {
                                fragment = fragment.substring(1);
                            }
                            if (!fragment.startsWith("/")) {
                                fragment = "/" + fragment;
                            }
                            replacementNode = resolvedFileRoot.at(fragment);
                            if (replacementNode.isMissingNode()) {
                                throw new MojoExecutionException("Fragment " + fragment + " not found in " + targetFile.getAbsolutePath());
                            }
                        }
                        return replacementNode;
                    }
                }
            }

            java.util.Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();
            java.util.List<String> fieldNames = new java.util.ArrayList<>();
            while (fields.hasNext()) {
                fieldNames.add(fields.next().getKey());
            }
            for (String fieldName : fieldNames) {
                JsonNode child = objNode.get(fieldName);
                JsonNode resolvedChild = resolveReferences(child, currentFile, visitedPaths);
                objNode.set(fieldName, resolvedChild);
            }
            return objNode;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode child = arrayNode.get(i);
                JsonNode resolvedChild = resolveReferences(child, currentFile, visitedPaths);
                arrayNode.set(i, resolvedChild);
            }
            return arrayNode;
        }
        return node;
    }

    private ObjectMapper getMapperForFile(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".json")) {
            return jsonMapper;
        }
        return yamlMapper;
    }

    private String combineProtobuf(File file) throws MojoExecutionException {
        try {
            File canonicalFile = file.getCanonicalFile();
            Set<String> visited = new LinkedHashSet<>();
            visited.add(canonicalFile.getCanonicalPath());
            return combineProtobufInternal(canonicalFile, visited, true);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to get canonical path", e);
        }
    }

    private String combineProtobufInternal(File file, Set<String> visitedPaths, boolean isRoot) throws MojoExecutionException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read Protobuf file: " + file.getAbsolutePath(), e);
        }

        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            Matcher importMatcher = importPattern.matcher(line);
            if (importMatcher.matches()) {
                String importedPath = importMatcher.group(1);
                File importedFile;
                try {
                    importedFile = new File(file.getParentFile(), importedPath).getCanonicalFile();
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to resolve relative import: " + importedPath, e);
                }

                if (importedFile.exists()) {
                    String canonicalImported;
                    try {
                        canonicalImported = importedFile.getCanonicalPath();
                    } catch (IOException e) {
                        throw new MojoExecutionException("Failed to get canonical path", e);
                    }

                    if (visitedPaths.contains(canonicalImported)) {
                        throw new MojoExecutionException("Circular dependency detected: " + String.join(" -> ", visitedPaths) + " -> " + canonicalImported);
                    }

                    Set<String> nextVisited = new LinkedHashSet<>(visitedPaths);
                    nextVisited.add(canonicalImported);

                    String inlinedContent = combineProtobufInternal(importedFile, nextVisited, false);
                    sb.append(inlinedContent);
                    if (!inlinedContent.isEmpty() && !inlinedContent.endsWith("\n")) {
                        sb.append("\n");
                    }
                } else {
                    sb.append(line).append("\n");
                }
            } else {
                if (!isRoot) {
                    if (syntaxPattern.matcher(line).matches()) {
                        continue;
                    }
                    if (packagePattern.matcher(line).matches()) {
                        continue;
                    }
                }
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}