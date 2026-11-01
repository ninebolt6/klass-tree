package io.github.klasstree.graph

import kotlinx.serialization.Serializable

/**
 * Serializable description of one analysis run. The Gradle plugin writes it and the analyzer
 * process reads it, which keeps the Gradle daemon free of Analysis API / IntelliJ classes.
 */
@Serializable
data class AnalysisSpec(
    val projectRoot: String,
    val modules: List<ModuleSpec>,
    /** When true, any per-file analysis error fails the analyzer process. */
    val failOnAnalysisError: Boolean = false,

    /** When false, `.java` files under source roots are ignored entirely. */
    val includeJavaSources: Boolean = true,
)

@Serializable
data class ModuleSpec(
    /** Unique analysis module id, for example `:shared:main`. */
    val id: String,
    /** Gradle project path, for example `:shared`. */
    val projectPath: String,
    /** Gradle source set name, for example `main` or `test`. */
    val sourceSetName: String,
    /** Name registered in the Analysis API session. Must be unique. */
    val moduleName: String,
    val sourceRoots: List<String>,
    val classpath: List<String>,
    /** Ids of other [ModuleSpec]s this module depends on. */
    val dependencies: List<String> = emptyList(),
)
