# klass-tree

A Gradle plugin that writes a **source-level Kotlin dependency graph**. It uses the Kotlin
Analysis API (Standalone) for semantic resolution and produces:

- `graph.json` — the primary, platform-independent artifact;
- `graph.dot` — an optional Graphviz rendering derived from the JSON output.

The graph contains only declarations that exist in the analyzed source sets. References to JDK and
library classes are resolved and counted, but external nodes are not emitted by default.

## Status

Early milestone: the plugin analyzes Kotlin JVM projects and emits the graph structure. Kotlin
Multiplatform, Android, and Java sources are not supported yet.

## Requirements

- JDK 21 (the analyzer is compiled for and launched on a Java 21 runtime);
- Gradle 9.x;
- Kotlin JVM Gradle plugin `2.5.0-Beta2` (the version this plugin is built and tested against);
- `mavenCentral()` available to the consuming build so that the isolated
  `klassTreeAnalyzer` configuration can resolve the Analysis API.

The plugin itself uses Kotlin Gradle plugin API types. The Kotlin JVM plugin must therefore be on
the **root buildscript classpath**, even if it is only declared with `apply false`.

## Using the plugin

This repository is not published yet. Consume it as an included build:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../klass-tree")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts (root project)
plugins {
    kotlin("jvm") version "2.5.0-Beta2" apply false
    id("io.github.klass-tree")
}
```

Apply `org.jetbrains.kotlin.jvm` to every project that should be analyzed. The plugin must be
applied to the root project; applying it elsewhere fails with an explicit error.

Then run:

```shell
./gradlew klassTree
```

The task is up-to-date checked against the analyzed Kotlin sources and supports Gradle's
configuration cache.

## Configuration

```kotlin
klassTree {
    // Analyze test source sets in addition to main. Default: false.
    includeTests.set(false)

    // Output formats. JSON is always written; DOT is derived from the JSON. Default: [json, dot].
    formats.set(setOf("json", "dot"))

    // Directory receiving graph.json, graph.dot, and the intermediate analysis-spec.json.
    outputDirectory.set(layout.buildDirectory.dir("klass-tree"))

    // Version of org.jetbrains.kotlin:kotlin-analysis-api used by the forked analyzer.
    analysisApiVersion.set("2.5.0-Beta2")

    // Fail the build when at least one source file could not be analyzed. Default: false.
    failOnAnalysisError.set(false)

    // Extra JVM arguments for the forked analyzer process, for example ["-Xmx2g"].
    analyzerJvmArgs.set(emptyList())
}
```

| Option | Type | Default |
| --- | --- | --- |
| `includeTests` | `Property<Boolean>` | `false` |
| `formats` | `SetProperty<String>` | `["json", "dot"]` |
| `outputDirectory` | `DirectoryProperty` | `build/klass-tree` |
| `analysisApiVersion` | `Property<String>` | `2.5.0-Beta2` |
| `failOnAnalysisError` | `Property<Boolean>` | `false` |
| `analyzerJvmArgs` | `ListProperty<String>` | `[]` |

`formats` must be a non-empty subset of `json` and `dot`. JSON is always produced, even when only
`dot` is requested, because DOT is rendered from the serialized JSON.

## Outputs

| File | Description |
| --- | --- |
| `build/klass-tree/graph.json` | Primary graph artifact. |
| `build/klass-tree/graph.dot` | Graphviz rendering, written when `dot` is in `formats`. |
| `build/klass-tree/analysis-spec.json` | Intermediate module/classpath description passed to the analyzer. |

## What is analyzed

- Every project in the build that applies `org.jetbrains.kotlin.jvm`.
- The `main` source set by default; all non-main compilation source sets when `includeTests` is on.
- Kotlin sources only. Script files are skipped.

Resolution is semantic: the analyzer resolves types, calls, property accesses, annotations, and
operator/synthetic calls through the Analysis API. Import statements do not create edges, and
external JVM/library declarations are excluded from the graph by default. The resolution counters
in `stats.resolution` report how many candidates were resolved to source, resolved to external
declarations, left unresolved, or skipped.

## Node rules

Graph nodes are **source declarations only**:

| `kind` | Declared as |
| --- | --- |
| `CLASS` | `class` (including `data`, `sealed`, `value`, etc.) |
| `INTERFACE` | `interface` |
| `OBJECT` | `object`, including `companion object` |
| `ENUM` | `enum class` |
| `ANNOTATION` | `annotation class` |
| `TYPE_ALIAS` | `typealias` |
| `FILE_SCOPE` | A file containing at least one top-level function or property |

Rules:

- Members are aggregated into their owning class or object. There are no member-level nodes in
  this version.
- Local classes and local declarations are not indexed.
- Enum entries are not nodes; they are members of the enum node.
- Top-level functions and properties aggregate into one `FILE_SCOPE` node per file.
- A top-level type alias gets its own `TYPE_ALIAS` node rather than joining the file scope.
- A file that contains only class-like declarations and type aliases has no `FILE_SCOPE` node.
- Nested classes are nodes, and their members aggregate into the nested class.
- Anonymous objects are not nodes; references inside them aggregate to the enclosing declaration.

### Node IDs

```
<module-id>:<relative-source-path>#<KIND>:<fully-qualified-name>
<module-id>:<relative-source-path>#FILE_SCOPE
```

The module id is the Gradle project path plus source set, for example `:shared:main`. Including
the module, path, and declaration kind keeps same-named or nested declarations distinct.

## Edge rules

An edge means "the `from` declaration depends on the `to` declaration". The source is the
innermost owning class/object, or the file's `FILE_SCOPE` for top-level code. Self-edges are
dropped.

| `kind` | Created by |
| --- | --- |
| `TYPE_REFERENCE` | Type usages: parameters, return types, properties, generic arguments, casts, etc. |
| `INHERITANCE` | Superclass and superinterface entries |
| `CONSTRUCTOR_CALL` | Constructor invocations, including implicit constructors and annotation constructors |
| `FUNCTION_CALL` | Resolved function calls |
| `PROPERTY_ACCESS` | Resolved property reads/writes and qualified property accesses |
| `CALLABLE_REFERENCE` | Callable references such as `User::displayName` |
| `ANNOTATION` | Annotation usages |
| `OPERATOR_OR_SYNTHETIC_CALL` | Operator conventions, array access, and synthetic calls |

Repeated references of the same `(from, to, kind)` are merged into one edge with multiple
`callSites`, sorted by file, line, and column.

Symbol origin handling:

- `SOURCE` symbols are included.
- `SOURCE_MEMBER_GENERATED` symbols are included only when they are constructors, so implicit
  primary constructors and annotation constructors connect to their source class. Other generated
  members (`copy`, `componentN`, etc.) are skipped.
- `LIBRARY` and `JAVA_LIBRARY` symbols count as external and do not produce edges.
- Java source symbols, compiler plugin declarations, packages, type parameters, and local
  declarations are skipped.

## JSON schema

`schemaVersion` is currently `1`.

```json
{
  "schemaVersion": 1,
  "generator": "klass-tree",
  "generatedAt": "2026-11-01T04:45:27.409574Z",
  "projectRoot": "/absolute/path/to/project",
  "nodes": [
    {
      "id": ":shared:main:shared/src/main/kotlin/com/example/shared/User.kt#CLASS:com.example.shared.User",
      "kind": "CLASS",
      "name": "User",
      "fqName": "com.example.shared.User",
      "module": "shared",
      "sourceSet": "main",
      "package": "com.example.shared",
      "sourceFile": "shared/src/main/kotlin/com/example/shared/User.kt",
      "line": 7,
      "column": 12
    }
  ],
  "edges": [
    {
      "from": ":api:main:api/src/main/kotlin/com/example/api/Main.kt#FILE_SCOPE",
      "to": ":shared:main:shared/src/main/kotlin/com/example/shared/User.kt#CLASS:com.example.shared.User",
      "kind": "TYPE_REFERENCE",
      "callSites": [
        {
          "file": "api/src/main/kotlin/com/example/api/Main.kt",
          "line": 10,
          "column": 18
        }
      ]
    }
  ],
  "stats": {
    "nodeCount": 13,
    "edgeCount": 34,
    "callSiteCount": 40,
    "sourceFileCount": 9,
    "elapsedMillis": 2264,
    "resolution": {
      "candidates": 160,
      "resolvedSource": 56,
      "resolvedExternal": 40,
      "unresolved": 1,
      "skipped": 63,
      "selfEdgesSkipped": 16,
      "analysisErrors": []
    }
  }
}
```

Notes:

- `module` is the Gradle project path without the leading colon (or `root` for the root project).
- `sourceFile` and call-site `file` paths are relative to `projectRoot`, with forward slashes.
- `line` and `column` are 1-based.
- `fqName` is omitted when it cannot be determined. Nullable fields are omitted from the JSON.
- `stats.resolution.analysisErrors` lists files that failed to analyze. They fail the build only
  when `failOnAnalysisError` is enabled.

## Architecture

The project is split into three modules:

| Module | Responsibility |
| --- | --- |
| `graph-core` | Platform-independent graph model, `AnalysisSpec`, edge merging, JSON and DOT exporters. No Gradle or Analysis API dependency. |
| `analyzer` | Analysis API Standalone session construction, declaration indexing, PSI candidate collection, semantic resolution, and the forked entry point. |
| `gradle-plugin` | Root plugin, extension, per-source-set module-spec tasks, and the task that launches the analyzer. |

Data flow:

1. The plugin registers one module-spec task per selected Kotlin JVM source set. At execution,
   those tasks collect source roots and external compile dependencies; the root task aggregates
   their outputs and serializes an `AnalysisSpec`.
2. `KlassTreeTask` forks a separate JVM with two classpaths: the isolated `klassTreeAnalyzer`
   configuration (Analysis API and its transitive dependencies) and the plugin's own runtime.
3. The analyzer builds a Standalone Analysis API session, indexes source declarations, resolves
   PSI references, and builds the graph.
4. `graph.json` is written first. `graph.dot` is produced by decoding that JSON and rendering it,
   so the two outputs cannot drift apart.

The analyzer runs in a forked JVM because Analysis API Standalone installs IntelliJ platform
services that are not safe to load and unload inside the Gradle daemon. The plugin keeps Kotlin
Gradle plugin API usage isolated in `KotlinProjectScanner` and declares KGP as `compileOnly`, so
the plugin reports a clear error instead of failing with a linkage error when KGP is absent.

## Fixture

The `fixtures` build is a two-module Kotlin JVM project that exercises inheritance, type
references, nested generics, constructor calls, function/property calls, callable references,
annotations, operators, cross-module edges, and test source sets.

```shell
# From the repository root:
./gradlew -p fixtures klassTree
```

Include test sources:

```shell
./gradlew -p fixtures klassTree -PklassTreeTests=true
```

Outputs are written to `fixtures/build/klass-tree/`.

## Limitations

- Kotlin JVM only; Kotlin Multiplatform and Android are not handled.
- Java sources are skipped.
- Project dependencies are mapped to the target project's `main` module.
- Dependencies on projects from included builds are not put on the analysis classpath because
  project components are filtered out.
- External library declarations do not become nodes or edges.
- Members are aggregated into their owning class or file scope; there are no member-level nodes.
- Generated members other than constructors are excluded.
- Analysis is not incremental within a run: the task reruns when analyzed sources or the
  classpath change, and re-analyzes all selected modules.
- The Analysis API version must stay compatible with the Kotlin compiler version used by the
  analyzed build.

## Development

```shell
./gradlew build          # compile, test, and check all modules
./gradlew :analyzer:test # analyzer integration test
./gradlew -p fixtures klassTree
```

The root build uses Kotlin `2.5.0-Beta2`, JVM target 21, and JUnit 5.
