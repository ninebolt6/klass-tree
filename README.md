# klass-tree

A Gradle plugin that writes a **source-level Kotlin dependency graph** for Kotlin JVM projects.
It resolves references semantically with the Kotlin Analysis API (Standalone) and produces:

- `graph.json` — the primary, platform-independent artifact;
- `graph.dot` — an optional Graphviz rendering derived from the JSON output.

Only declarations in the analyzed source sets become nodes. References to JDK and library classes
are resolved and counted in `stats.resolution`, but no external nodes are emitted.

Early milestone: Kotlin Multiplatform, Android, and Java sources are not supported yet.

## Requirements

- JDK 21 (the analyzer runs on a Java 21 runtime);
- Gradle 9.x;
- Kotlin JVM Gradle plugin `2.5.0-Beta2` on the **root buildscript classpath** — the plugin uses
  KGP API types, so it must be declared even if only with `apply false`;
- `mavenCentral()` so the isolated `klassTreeAnalyzer` configuration can resolve the Analysis API.

## Using the plugin

Not published yet; consume it as an included build:

```kotlin
// settings.gradle.kts
pluginManagement {
    includeBuild("../klass-tree")
    repositories {
        mavenCentral()
        gradlePluginPortal()
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

```shell
./gradlew klassTree
```

The task is up-to-date checked and supports the configuration cache.

`example/` is a runnable two-module project wired exactly like this; see [Example](#example).

## Configuration

```kotlin
klassTree {
    // Analyze test source sets in addition to main. Default: false.
    includeTests.set(false)

    // Analyze .java files alongside Kotlin sources. Default: true. Disable only to fall back to
    // Kotlin-only analysis; mixed codebases silently lose Java-originated edges when off.
    includeJavaSources.set(true)

    // Non-empty subset of "json" and "dot". JSON is always written because DOT is rendered from
    // it. Default: ["json", "dot"].
    formats.set(setOf("json", "dot"))

    // Directory receiving graph.json, graph.dot, and the intermediate analysis-spec.json.
    // Default: build/klass-tree.
    outputDirectory.set(layout.buildDirectory.dir("klass-tree"))

    // Version of org.jetbrains.kotlin:kotlin-analysis-api used by the forked analyzer.
    analysisApiVersion.set("2.5.0-Beta2")

    // Fail the build when any file or reference could not be analyzed. Default: false.
    failOnAnalysisError.set(false)

    // Extra JVM arguments for the forked analyzer process, for example ["-Xmx2g"]. Default: [].
    analyzerJvmArgs.set(emptyList())

    // Baseline graph that klassTreeCheck compares against. Commit a graph.json under this name to
    // freeze the current state. Default: klass-tree-baseline.json in the root project.
    baselineFile.set(layout.projectDirectory.file("klass-tree-baseline.json"))

    // fqName/package prefixes that must not gain new references relative to the baseline, for
    // example "com.example.legacy". Existing edges are frozen by the baseline, not denied outright.
    deniedTargets.set(listOf("com.example.legacy"))
}
```

## Checking dependency regressions

`klassTreeCheck` compares `graph.json` with the committed baseline and fails when new references
to `deniedTargets` appear. It always writes the full diff to `klass-tree-diff.json` next to the
graph so CI can archive it.

```shell
./gradlew klassTree                                   # 1. produce graph.json
cp build/klass-tree/graph.json klass-tree-baseline.json  # 2. freeze as the baseline, commit it
./gradlew klassTreeCheck                              # 3. run in CI from then on
```

Violations list each new crossing with its call site:

```text
klass-tree: 2 new reference(s) to denied targets [com.example.legacy]:
    + com.example.api.Service -> com.example.legacy.GodClass CONSTRUCTOR_CALL @ api/Service.kt:14
```

Only references *absent from the baseline* count, so pre-existing dependencies never fail; removed
references are reported as progress. This is the intended workflow for shrinking a monolithic
module: declare the code being phased out as a denied target and let CI enforce the ratchet.

## What is analyzed

- Every project that applies `org.jetbrains.kotlin.jvm`; `main` by default, all compilation
  source sets when `includeTests` is on.
- Kotlin and Java sources from the same source sets; script files are skipped. Java sources come
  from the matching Java `SourceSet` (`src/<name>/java`) and can be excluded with
  `includeJavaSources`.
- Types, calls, property accesses, annotations, and operator/synthetic calls are resolved
  semantically. Imports and package statements do not create edges.
- Java-to-Kotlin references resolve through the Kotlin light classes, so a Java call to a Kotlin
  top-level function lands on the file's `FILE_SCOPE` node.

## Node rules

| `kind` | Declared as |
| --- | --- |
| `CLASS` | `class` (including `data`, `sealed`, `value`, etc.) |
| `INTERFACE` | `interface` |
| `OBJECT` | `object`, including `companion object` |
| `ENUM` | `enum class` |
| `ANNOTATION` | `annotation class` |
| `TYPE_ALIAS` | `typealias` |
| `FILE_SCOPE` | A file containing at least one top-level function or property |

- Members are aggregated into their owning class or object; there are no member-level nodes.
  Enum entries, local and anonymous declarations are not nodes — references inside them aggregate
  to the enclosing declaration.
- A file with only class-like declarations and type aliases has no `FILE_SCOPE` node.

Node IDs are `<module-id>:<relative-source-path>#<KIND>:<fq-name>` (or `#FILE_SCOPE`), where the
module id is the project path plus source set, for example `:shared:main`.

## Edge rules

An edge means "the `from` declaration depends on the `to` declaration". The source is the
innermost owning class/object, or the file's `FILE_SCOPE` for top-level code. Self-edges are
dropped. Repeated references of the same `(from, to, kind)` merge into one edge with `callSites`
sorted by file, line, and column.

| `kind` | Created by |
| --- | --- |
| `TYPE_REFERENCE` | Type usages: parameters, return types, properties, generic arguments, casts |
| `INHERITANCE` | Superclass and superinterface entries |
| `CONSTRUCTOR_CALL` | Constructor invocations, including implicit and annotation constructors |
| `FUNCTION_CALL` | Resolved function calls |
| `PROPERTY_ACCESS` | Resolved property reads/writes and qualified property accesses |
| `CALLABLE_REFERENCE` | Callable references such as `User::displayName` |
| `ANNOTATION` | Annotation usages |
| `OPERATOR_OR_SYNTHETIC_CALL` | Operator conventions, array access, delegated-property access, for-loop iteration, destructuring `componentN`, and synthetic calls |

Generated members are skipped except constructors (so `Foo()` and `@Foo` connect to their class).
Library, Java-source, package, type-parameter, and local symbols do not produce edges; they are
counted in `stats.resolution` instead.

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
    "elapsedMillis": 1657,
    "resolution": {
      "candidates": 147,
      "resolvedSource": 56,
      "resolvedExternal": 40,
      "unresolved": 0,
      "skipped": 51,
      "selfEdgesSkipped": 16,
      "analysisErrors": []
    }
  }
}
```

- `module` is the project path without the leading colon (`root` for the root project).
- Paths are relative to `projectRoot` with forward slashes; `line`/`column` are 1-based.
- Nullable fields are omitted.
- `analysisErrors` lists files or individual references that failed; it fails the build only when
  `failOnAnalysisError` is enabled.

## Architecture

| Module | Responsibility |
| --- | --- |
| `klass-tree-graph-core` | Platform-independent graph model, `AnalysisSpec`, edge merging, and the JSON exporter. |
| `klass-tree-graph-dot` | Graphviz DOT rendering. Optional: depend on it directly if you only need DOT from `graph.json`. |
| `klass-tree-analyzer` | Analysis API Standalone session, declaration indexing, PSI candidate collection, semantic resolution, and the forked entry point. |
| `klass-tree-gradle-plugin` | Root plugin, extension, per-source-set module-spec tasks, and the task that launches the analyzer. |

Data flow:

1. A module-spec task per selected source set collects source roots and external compile
   dependencies; the root task aggregates them into `analysis-spec.json`.
2. `KlassTreeTask` forks a JVM with the isolated `klassTreeAnalyzer` configuration plus the
   plugin's own runtime. The fork is required because Analysis API Standalone installs IntelliJ
   services that are not safe to load into the Gradle daemon.
3. The analyzer indexes source declarations, resolves PSI references, and builds the graph.
4. `graph.json` is written first; `graph.dot` is rendered from that JSON by `klass-tree-graph-dot`
   (`DotExporter`) so the two cannot drift.

## Example

`example/` is a standalone build that consumes the plugin through `includeBuild("..")` and
analyzes two modules (`api` depends on `shared`).

```shell
./gradlew -p example klassTree                    # main sources
./gradlew -p example klassTree -PklassTreeTests=true  # include test sources
```

Outputs go to `example/build/klass-tree/`.

## Limitations

- Kotlin JVM only (Kotlin Multiplatform, Android, and other platforms are not supported). Java
  sources in the analyzed source sets are included unless `includeJavaSources` is off; Java
  language constructs without a Kotlin edge-kind equivalent (e.g. static-initializer reads) map
  to the closest kind.
- Project dependencies map to the target project's `main` module. Dependencies on projects from
  included builds are not on the analysis classpath because project components are filtered out.
- Analysis is not incremental within a run: the task re-analyzes all selected modules.
- The Analysis API version must stay compatible with the Kotlin compiler of the analyzed build.
- The implicit `Companion` name does not resolve on a companion object declared with an explicit
  name (Analysis API limitation); such calls are counted in `unresolved`. Use the declared name
  or `ContainingClass.call()` instead.

## Development

```shell
./gradlew build          # compile, test, and check all modules
./gradlew :klass-tree-analyzer:test # analyzer integration test
./gradlew -p example klassTree
```
