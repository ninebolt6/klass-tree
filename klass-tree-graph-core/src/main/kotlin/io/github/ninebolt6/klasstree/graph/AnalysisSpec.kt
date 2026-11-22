package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

@Serializable
data class AnalysisSpec(
    val projectRoot: String,
    val modules: List<ModuleSpec>,
    val failOnAnalysisError: Boolean = false,
    val includeJavaSources: Boolean = true,
)

@Serializable
data class ModuleSpec(
    val id: String,
    val projectPath: String,
    val sourceSetName: String,
    /** Name registered in the Analysis API session. Must be unique. */
    val moduleName: String,
    val sourceRoots: List<String>,
    val classpath: List<String>,
    val dependencies: List<String> = emptyList(),
)

val ModuleSpec.moduleLabel: String
    get() = projectPath.removePrefix(":").ifEmpty { "root" }
