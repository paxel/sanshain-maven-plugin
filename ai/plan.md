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
    - Parameters: `serviceName`, `branch`, `openApiFile`.
- `sanshain:require`:
    - Reads a configuration of required endpoints.
    - Fetches snippets from `GET /require` on the SanShain service.
    - Saves snippets to a specified directory for use by code generators.

## License
This project is licensed under the GNU Affero General Public License (AGPL-3.0), same as the SanShain Service.
