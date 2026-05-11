---
sessionId: session-260511-220952-1kfv
---

# Requirements

### Overview & Goals
The goal is to provide a seamless experience when using the Sanshain plugin in a company-wide parent POM. This involves clarifying how to configure the plugin to run across all child projects and ensuring that the `bestEffort` mode truly prevents any build failures, even when strict configuration checks would normally trigger an abort.

### Scope
- **In Scope**:
    - Enhancing `SanshainMojoDelegate.abort()` to respect the `bestEffort` flag.
    - Updating `ProvideMojo` and `RequireMojo` to pass the `bestEffort` status to the abort logic.
    - Comprehensive documentation in `README.md` for Parent POM integration.
    - Clarification on goal execution vs. error handling.
- **Out of Scope**:
    - Adding new goals to the plugin.
    - Changing the default behavior of Maven (how it discovers and runs plugins).

# Technical Design

### Unified Abort Handling
We will update the `abort` method in `SanshainMojoDelegate` to handle both `strict` and `bestEffort` modes:
```java
public void abort(String message, boolean bestEffort) throws MojoExecutionException {
    if (bestEffort) {
        log.warn("Sanshain goal skipped (best effort): " + message);
        return;
    }
    if (strict) {
        throw new MojoExecutionException(message);
    }
    log.warn(message + " Skipping. Set sanshain.strict=true to fail in this case.");
}
```

### Proposed Changes

#### `SanshainMojoDelegate.java`
- Update `abort(String message)` to `abort(String message, boolean bestEffort)`.

#### `ProvideMojo.java` & `RequireMojo.java`
- Update all calls to `delegate.abort(...)` to include the resolved `bestEffort` flag.

#### `README.md`
- Revise the "Using in Parent POMs" section.
- Provide a clear, copy-pasteable XML example for adding the plugin and both goals (`provide`, `require`) to the parent POM's `<build><plugins>` section.
- Explicitly address the user's question: `bestEffort` does not trigger goals; it ensures they are safe to run globally by suppressing failures.

# Testing

### Validation Approach
- **Unit Tests**:
    - Verify that `bestEffort=true` suppresses a `MojoExecutionException` when `strict=true` but configuration (like `serviceName`) is missing.
    - Verify that the log message correctly identifies the "best effort" skip.

### Key Scenarios
- `strict=true`, `bestEffort=true`, missing `serviceName` -> Build Success (Warning).
- `strict=true`, `bestEffort=false`, missing `serviceName` -> Build Failure.
- `strict=false`, `bestEffort=false`, missing `serviceName` -> Build Success (Warning).

# Delivery Steps

### ✓ Step 1: Update abort logic in SanshainMojoDelegate
Enhance `abort` to support the `bestEffort` flag.
- Change `abort(String message)` to `abort(String message, boolean bestEffort)`.
- Implement logic to warn and return if `bestEffort` is true, bypassing `strict` mode.

### ✓ Step 2: Refactor Mojo calls to abort
Update `ProvideMojo` and `RequireMojo` to use the new `abort` logic.
- Pass the resolved `bestEffort` flag to all `delegate.abort()` calls.

### ✓ Step 3: Update documentation and tests for Parent POM
Finalize the Parent POM integration guide and verify robustness.
- Update `README.md` with full `<build><plugins>` configuration and clarification on goal execution.
- Add unit tests to verify that `bestEffort` overrides `strict` in `abort()`.