package io.github.klasstree.gradle

import kotlinx.serialization.Serializable

/**
 * Raw per-source-set module description emitted by each [KlassTreeModuleSpecTask].
 * [ModuleSpecs.build] merges the candidates of all analyzed projects into the analyzer-facing
 * `ModuleSpec` list, computing dependency module ids and unique module names.
 */
@Serializable
internal data class AnalysisModuleCandidate(
    val projectPath: String,
    val sourceSetName: String,
    val moduleId: String,
    val moduleName: String,
    val sourceRoots: List<String>,
    val classpath: List<String>,
    val projectDependencyPaths: List<String>,
    val associatedModuleIds: List<String>,
)
