package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

@Serializable
data class GraphDiff(
    val addedNodes: List<GraphNode> = emptyList(),
    val removedNodes: List<GraphNode> = emptyList(),
    val addedEdges: List<GraphEdge> = emptyList(),
    val removedEdges: List<GraphEdge> = emptyList(),
    val edgeDeltas: List<GraphEdgeDelta> = emptyList(),
) {
    val hasChanges: Boolean
        get() = addedNodes.isNotEmpty() || removedNodes.isNotEmpty() ||
            addedEdges.isNotEmpty() || removedEdges.isNotEmpty() || edgeDeltas.isNotEmpty()
}
