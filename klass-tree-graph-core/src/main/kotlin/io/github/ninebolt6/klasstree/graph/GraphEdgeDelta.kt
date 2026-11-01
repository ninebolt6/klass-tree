package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

/** Call-site changes on an edge that exists in both the baseline and the current graph. */
@Serializable
data class GraphEdgeDelta(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val addedCallSites: List<CallSite> = emptyList(),
    val removedCallSites: List<CallSite> = emptyList(),
)
