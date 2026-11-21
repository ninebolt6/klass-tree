package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

@Serializable
data class DependencyBaseline(
    val schemaVersion: Int = 1,
    val edges: List<BaselineEdge>,
) {
    init {
        require(schemaVersion == 1) { "Unsupported baseline schema version: $schemaVersion" }
    }
}

@Serializable
data class BaselineEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val callSites: Int,
    val toFqName: String? = null,
    val toPackage: String? = null,
)
