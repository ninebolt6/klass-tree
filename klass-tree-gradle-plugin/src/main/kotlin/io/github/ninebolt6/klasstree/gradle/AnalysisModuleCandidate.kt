package io.github.ninebolt6.klasstree.gradle

import kotlinx.serialization.Serializable

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
