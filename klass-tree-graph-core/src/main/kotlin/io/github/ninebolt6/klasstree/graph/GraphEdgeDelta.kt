package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

@Serializable
data class GraphEdgeDelta(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val addedCallSites: List<CallSite> = emptyList(),
    val removedCallSites: List<CallSite> = emptyList(),
)
