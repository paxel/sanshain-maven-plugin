# Implementation Plan: SanShain Maven Plugin

This project provides a Maven plugin to interact with the SanShain Service during the build process.

## Purpose
The SanShain Maven Plugin allows microservices to:
1.  **Provide**: Upload their full OpenAPI specifications to the SanShain service (typically during `package` or `deploy` phase).
2.  **Require**: Download specific endpoint YAML snippets before the code generation phase, enabling fine-grained dependency management.

## Goals
- `sanshain:provide`:
    - Reads a local `openapi.yaml` file.
    - Sends it to `POST /provide` on the SanShain service.
    - Parameters: `serviceName`, `branch` (autodetected from Git if not specified), `openApiFile`.
- `sanshain:require`:
    - Reads a configuration of required endpoints.
    - Fetches snippets from `GET /require` on the SanShain service.
    - Saves snippets to a specified directory (typically `target/generated-sources/sanshain`) for use by code generators.

## Implementation Details

### Git Branch Extraction
- The `branch` parameter for the `provide` goal should be automatically extracted from the project's Git repository using the JGit library if not explicitly provided.
- JGit provides a native Java implementation for Git operations, avoiding the need for external process execution.
- This ensures consistency and reduces manual configuration.
- It can be overridden via `sanshain.yaml` or Maven properties.

### Server Communication
- Communication with the SanShain service is performed via HTTP/HTTPS.
- The `sanshainUrl` parameter defines the base URL of the service.
- The `provide` goal uses a `POST` request with the OpenAPI content.
- The `require` goal uses a `GET` request with query parameters for the required endpoints.

### Target Path & Code Generation
- Downloaded snippets are stored in `target/generated-sources/sanshain` by default.
- This directory should be treated as a source root for code generators like `openapi-generator-maven-plugin`.
- For clients, the generated code should be placed in `target/generated-sources/openapi`, keeping it separate from manually written code.
- Since `openapi-generator-maven-plugin` typically takes a single input file, multiple requirements might need to be merged or processed individually if the generator supports it, or multiple executions of the generator plugin can be configured.

## Testing Strategy
- Unit tests for configuration loading and Git branch extraction.
- Integration tests (using `maven-verifier` or similar) to simulate a full build cycle with a mock SanShain service.
- Manual verification against a running `SanshainService`.

## License
This project is licensed under the GNU Affero General Public License (AGPL-3.0), same as the SanShain Service.
