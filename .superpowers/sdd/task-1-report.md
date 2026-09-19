# Task 1 Report: Spring Modulith foundation

## Status

Complete. Task 1 implements and verifies the Spring Modulith foundation on the `feat/ford-service-mvp` worktree.

## Commit

The task commit carries the required message:

`feat(modulith): add verified application module foundation`

## Changed files

### Build and runtime configuration

- `ford-ai-service/gradle/libs.versions.toml`
  - Added the Spring Modulith `2.1.1` version and one Modulith BOM alias.
  - Added aliases for `spring-modulith-starter-jdbc`, `spring-modulith-starter-insight`, and `spring-modulith-starter-test`.
- `ford-ai-service/build.gradle.kts`
  - Imported the single Modulith BOM.
  - Added the JDBC starter as an implementation dependency.
  - Added the Insight starter as a runtime-only dependency.
  - Added the test starter as a test dependency.
- `ford-ai-service/src/main/java/com/ford/service/FordServiceApplication.java`
  - Added `@Modulithic(systemName = "ford-service")`.
- `ford-ai-service/src/main/resources/application.yml`
  - Enabled runtime verification.
  - Configured event completion mode `update`.
  - Configured published, processing, and resubmitted staleness at `10m` with a `1m` check interval.
  - Disabled internal event externalization.
  - Exposed the `modulith` actuator resource alongside the existing management resources.

### Tests and documentation

- Added `ford-ai-service/src/test/java/com/ford/service/architecture/ApplicationModulesTest.java` using:
  `ApplicationModules.of(FordServiceApplication.class).verify()`.
- Added `ford-ai-service/src/test/java/com/ford/service/architecture/ModuleDocumentationTest.java` using all required `Documenter` writers.

### Smallest boundary changes required by verification

- Moved the knowledge-owned `EmbeddingPort` and `KnowledgeRetriever` contracts from the AI output-port package to `knowledge.application.port.out`, preserving their APIs and updating their consumers/tests.
- Added named interfaces for the existing public application contracts in catalog, finance, knowledge, and knowledge output ports.
- Removed `final` from Spring-managed classes that the Insight runtime must proxy. No behavior or public method contracts changed.
- Marked the existing `PiiObservationFilter` as infrastructure so the Insight observation post-processor does not create a bean-initialization cycle. Its redaction behavior is unchanged.

## TDD evidence

1. **Red: module test first.** The initial focused run failed at test compilation because `org.springframework.modulith.core` was not yet available.
2. **Red: real architecture violations surfaced.** After adding Modulith, verification reported the AI/knowledge cycle and internal-access violations. The implementation resolved those specific boundaries with moved public contracts and named interfaces; no blanket exclusions were added.
3. **Green: focused architecture tests.** `ApplicationModulesTest` and `ModuleDocumentationTest` passed.
4. **Green: application context.** `FordServiceApplicationTest` passed after the minimal Insight proxy/initialization compatibility adjustments.
5. **Green: complete unit suite.** `./gradlew test` passed with 56 tests, 0 failures, 0 errors, and 0 skipped tests.

The task brief names `./gradlew :ford-ai-service:test`; this repository has the Gradle wrapper in `ford-ai-service`, so the equivalent command was run from that module as `./gradlew test`.

## Generated documentation inspection

The documentation test generated files under `ford-ai-service/build/spring-modulith-docs`:

- 15 PlantUML files, including `components.puml` and one diagram per module.
- 14 module canvas `.adoc` files.
- A non-empty `all-docs.adoc` aggregating document.
- `components.puml` contains module relationship entries.

## Dependency verification

The resolved test runtime graph contains:

- `spring-modulith-bom:2.1.1`
- `spring-modulith-starter-jdbc:2.1.1`
- `spring-modulith-starter-insight:2.1.1`
- `spring-modulith-starter-test:2.1.1`

No JPA, MongoDB, broker, LangGraph4j, or second migration dependency was introduced. The provider delivery outbox remains separate from Modulith's internal JDBC event publication registry.

## Self-review

- `ApplicationModules.verify()` is enabled both in the test and at runtime.
- Event completion and staleness properties use the resolved Spring Modulith 2.1.1 property names.
- Internal event externalization is explicitly disabled.
- No credentials, raw payloads, prompts, completions, or sensitive lead data were added to logs.
- The module graph passes without exclusions, and existing unit/application-context behavior remains green.
- `git diff --check` passes; line-ending warnings are pre-existing repository normalization warnings.

## Concerns and follow-up

- The existing security policy for HTTP actuator access was not redesigned in Task 1. The `modulith` resource is now exposed through the existing management configuration; authorization semantics remain governed by the existing `SecurityConfiguration` and should be reviewed with the later operator/security work.
- The full integration-test task was not run because Task 1's required verification is the unit/application-context suite and the integration suite requires external PostgreSQL/Testcontainers setup.
- Gradle reports its existing deprecation warning, and the current JDK/Netty combination reports a restricted native-access warning; neither caused test failures.
