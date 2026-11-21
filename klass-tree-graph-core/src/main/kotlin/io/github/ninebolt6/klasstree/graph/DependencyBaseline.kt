package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

@Serializable
data class DependencyBaseline(
    val schemaVersion: Int = 1,
    val edges: List<BaselineEdge>,
) {
    init {
        require(schemaVersion == 1) { "Unsupported baseline schema version: $schemaVersion" }
        require(edges.map { Triple(it.from, it.to, it.kind) }.distinct().size == edges.size) {
            "Duplicate baseline edges"
        }
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
) {
    init {
        require(callSites >= 0) { "Baseline call-site counts must not be negative" }
    }
}
