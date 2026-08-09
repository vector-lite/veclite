# Repository Guidelines

## Project Structure & Module Organization

This is a Java 17 vector-store library built with Gradle. Production code is in `src/main/java/veclite`, organized by responsibility: `api` exposes client-facing types, `engine` contains in-memory search and storage, `model` holds DTOs and enums, and `quantization`, `math`, `persistence`, `config`, `embedding`, and `web` provide supporting features. Keep new classes in the narrowest matching package.

Tests live in `src/test/java/veclite`. Test data belongs in `src/test/resources/datasets`; do not add generated stores to source control. Runtime resources are under `src/main/resources`, including Spring metadata in `META-INF`, versioned design notes in `design`, and benchmark reports in `report`.

## Build, Test, and Development Commands

Run commands from the Git project root (the directory containing `gradlew`):

```sh
./gradlew build                 # Compile, run tests, and package the library
./gradlew test                  # Run the JUnit 5 test suite
./gradlew test --tests 'veclite.LocalVectorStoreTest'  # Run one test class
./gradlew publishToMavenLocal   # Publish the 1.0.0 artifact locally
```

The test task uses a 2-6 GB heap. Run benchmark, stress, and accuracy classes such as `V24ComprehensiveBenchmarkTest` selectively; they may create large local vector-store artifacts.

## Coding Style & Naming Conventions

Use four-space indentation and standard Java brace placement. Follow the existing package naming (`veclite.engine`) and class naming (`LocalVectorStore`, `SQ8Quantizer`). Use `camelCase` for methods and fields, `UPPER_SNAKE_CASE` for constants, and expressive names for vector dimensions, offsets, and metrics. Keep public API validation explicit and use JUnit assertions rather than ad hoc output. No formatter, linter, or coverage threshold is configured; match surrounding code and keep diffs focused.

## Testing Guidelines

Use JUnit Jupiter (`@Test`, optional `@DisplayName`) and name tests `*Test.java`. Add focused regression tests beside related tests, covering normal behavior, invalid input, and boundary conditions. Use deterministic random seeds where randomized vectors are necessary. Run the affected class before the full suite.

## Commit & Pull Request Guidelines

Recent history uses concise type-prefixed subjects, especially `feat ...`, with short Chinese descriptions. Follow that pattern, for example `feat: optimize SQ8 precomputation` or `fix: preserve mmap payload offsets`. Keep each commit coherent. Pull requests should state the behavior change, link the issue when available, list tests run, and include benchmark or API evidence when performance or public behavior changes.
