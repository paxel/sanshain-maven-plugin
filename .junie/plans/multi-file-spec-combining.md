---
sessionId: session-260601-194627-10wn
---

# Requirements

### Overview & Goals
OpenAPI and AsyncAPI specifications often split complex schemas into multiple files using relative references (`$ref: "./dto.yaml"`). Similarly, Protocol Buffers (Protobuf) specifications use local import statements (`import "dto.proto"`). Since the Sanshain backend receives and processes single-file uploads, this feature adds a pre-upload **combining/bundling step** to recursively resolve local files, inline their contents, and upload a single self-contained document.

### Scope
- **In Scope:**
  - Local relative `$ref` resolution for OpenAPI and AsyncAPI files (both YAML and JSON formats), including fragment navigation (e.g., `#/components/schemas/User`).
  - Local relative `import` statement inlining for Protobuf files, with stripping of redundant `syntax`, `package`, and local `import` declarations from the imported files.
  - Multi-tiered configuration support: global Maven parameter, global environment variable override (`SANSHAIN_COMBINE`), global YAML config, and granular override per `provides` item in `sanshain.yaml`.
  - Infinite recursion protection through circular-dependency detection.
- **Out of Scope:**
  - Resolution of remote/networked references (e.g., URL-based `$ref: "https://example.com/dto.yaml"`).
  - Merging of different Protobuf syntax versions (e.g., mixing `proto2` and `proto3` across local files, which is syntactically invalid inside a single inlined file anyway).

### User Stories
- **As a Developer,** I want the Sanshain plugin to automatically bundle my multi-file OpenAPI/AsyncAPI files when providing contracts, so that I don't have to manually manage single-file schemas or configure third-party bundlers.
- **As an API Designer,** I want to use modular Protobuf files with local `import` statements and have them seamlessly combined during the upload process, ensuring Sanshain has the full context of my schema.

### Functional Requirements
- **FR-1 (YAML/JSON Combining):** When `combine` is active, any local `$ref` pointing to another YAML/JSON file relative to the current file must be replaced with the recursively-resolved, fully-inlined object.
- **FR-2 (Protobuf Combining):** When `combine` is active, local `.proto` imports must be recursively inlined. From the imported files, `syntax` and `package` lines must be stripped to keep the output a syntactically correct, single-file Protobuf specification.
- **FR-3 (Circular Safety):** The bundling process must detect cycles (file A references B, which references A) and fail-fast with a clear, descriptive `MojoExecutionException` containing the file paths involved.
- **FR-4 (Configurability):** Combining must be disabled by default and enabled via the `combine` configuration parameter or the `SANSHAIN_COMBINE` environment variable.

# Technical Design

### Current Implementation
- Currently, `ProvideMojo` reads a single configured file using `Files.readString(file.toPath())` and uploads it directly to Sanshain.
- There is no support for preprocessing files, resolving relative paths, or bundling.

### Key Decisions
- **Decision 1: Use Jackson for OpenAPI/AsyncAPI Resolution**
  - *Rationale:* We already depend on `jackson-databind` and `jackson-dataformat-yaml`. Traversing the parsed `JsonNode` tree recursively is lightweight, fully under our control, highly robust, and avoids introducing heavy, error-prone parser dependencies like `swagger-parser` or `asyncapi-parser`.
- **Decision 2: Line-by-Line Regex-based Protobuf Processing**
  - *Rationale:* Since Protobuf files are plain text, we can recursively locate local import statements using a robust regular expression and merge their definitions while stripping redundant header declarations (`syntax`, `package`).
- **Decision 3: Non-Local Import Exclusion**
  - *Rationale:* Standard Protobuf library imports (e.g., `import "google/protobuf/timestamp.proto";`) do not exist locally relative to the project. The combiner will check if the imported file exists relative to the parent file's directory. If not, it will leave the import statement completely untouched so that downstream parsers can resolve it standardly.

### Proposed Changes
1. **`SanshainConfig.java` & `SanshainMojoDelegate.java`:**
   - Add the `combine` field (boolean) to both global `SanshainConfig` and nested `ProvideConfig`.
   - Update `applyEnvOverrides()` in `SanshainConfig` to map the `SANSHAIN_COMBINE` environment variable.
   - Implement `resolveCombine()` in `SanshainMojoDelegate` to resolve the final active value.

2. **`SpecCombiner.java` (New Class):**
   - Implements recursive combining logic.
   - For OpenAPI/AsyncAPI:
     - Detects format (JSON/YAML) and uses corresponding Jackson `ObjectMapper`.
     - Recursively inspects `ObjectNode` fields for `$ref`.
     - If `$ref` is external and local, it loads the referenced file, navigates to the specified JSON fragment (e.g., `/components/schemas/User`), resolves any nested references within it, and replaces the `$ref` node with the resolved node.
   - For Protobuf:
     - Recursively reads lines, finding `import "some_local.proto";` statements.
     - If the file exists locally, it combines the file recursively, stripping `syntax` and `package` lines, and inlines the content.
   - Keeps track of canonical file paths to throw an exception if circular dependencies are detected.

3. **`ProvideMojo.java`:**
   - Exposes `combine` as a Mojo parameter: `@Parameter(property = "sanshain.combine")`.
   - Passes the active `combine` flag (resolved per file config) down to `provideFile()`.
   - If `combine` is enabled, calls `SpecCombiner.combine(file, apiType)` to obtain the preprocessed payload.

### File Structure
- `com.sanshain.maven.SpecCombiner` (new)
- `com.sanshain.maven.SanshainConfig` (modified)
- `com.sanshain.maven.SanshainMojoDelegate` (modified)
- `com.sanshain.maven.ProvideMojo` (modified)

# Testing

### Validation Approach
Verification will be performed entirely via JUnit 5 tests.

### Key Scenarios & Edge Cases to Test
1. **YAML/JSON Combining:**
   - Simple relative `$ref` inlining.
   - Nested multi-level relative `$ref` inlining.
   - JSON fragment `$ref: "dto.yaml#/definitions/Address"` navigation and extraction.
   - Circular reference detection (throwing `MojoExecutionException`).
2. **Protobuf Combining:**
   - Local import inlining and header stripping (removing `syntax` and `package`).
   - Multiple local imports inlining.
   - Retaining standard library imports (e.g., `google/protobuf/timestamp.proto`) that do not exist locally.
   - Circular import detection (throwing `MojoExecutionException`).
3. **Mojo Integration:**
   - Active `combine: true` flag triggering pre-processing of files before HTTP upload.
   - Configuration priority: `sanshain.yaml` entry override > system property > global config.

# Delivery Steps

### ✓ Step 1: Extend Plugin Configuration (SanshainConfig and SanshainMojoDelegate)
The plugin successfully parses and resolves the `combine` property from the YAML configuration, Maven parameters, and environment variables.

- Add the `combine` boolean field, getters, and setters to `SanshainConfig.java` and `SanshainConfig.ProvideConfig`.
- Add override support for the `SANSHAIN_COMBINE` environment variable in `SanshainConfig.applyEnvOverrides`.
- Implement `resolveCombine(Boolean combine, SanshainConfig config)` in `SanshainMojoDelegate.java`.
- Add unit tests to `SanshainConfigTest.java` to verify that the `combine` property is correctly deserialized and overridden.

### ✓ Step 2: Implement the SpecCombiner Utility
The new `SpecCombiner` utility recursively combines local relative references/imports for OpenAPI, AsyncAPI, and Protobuf files.

- Create `SpecCombiner.java` within the `com.sanshain.maven` package.
- Implement the recursive YAML/JSON tree traversal using Jackson to resolve relative `$ref` paths and inline their nodes, with support for JSON fragments.
- Implement recursive Protobuf parsing using Regex to identify local imports, inline their stripped definitions (retaining only messages, enums, etc.), and comment out or remove local import statements.
- Build canonical-path-based circular dependency detection to throw a clear `MojoExecutionException` if cycles are present.

### ✓ Step 3: Integrate SpecCombiner into ProvideMojo
The `sanshain:provide` goal executes the file combining logic when the `combine` option is active.

- Add the `@Parameter(property = "sanshain.combine")` Maven parameter to `ProvideMojo.java`.
- In `ProvideMojo.execute()`, resolve the global active `combine` value using the delegate.
- Update `processProvides()` and `provideFile()` to compute and pass the active `combine` flag per configured specification file.
- Update `provideFile()` to load and execute `SpecCombiner` when `combine` is enabled, using the combined content as the upload payload.

### ✓ Step 4: Complete Comprehensive Unit Testing & Validation
The SpecCombiner and Mojo integration are fully tested, and all tests pass under Maven.

- Create `SpecCombinerTest.java` with comprehensive test cases: YAML/JSON nested `$ref`s, fragment resolution, circular dependencies, local Protobuf imports, and ignored library imports.
- Add an integration test in `ProvideMojoTest.java` verifying that when `combine` is active, the Mojo combines the file and uploads the combined content.
- Run `mvn clean install` to compile and verify all unit tests.