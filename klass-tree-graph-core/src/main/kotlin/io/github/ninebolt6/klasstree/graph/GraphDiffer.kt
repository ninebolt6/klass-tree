package io.github.ninebolt6.klasstree.graph

/**
 * Compares two [DependencyGraph] snapshots by node id, edge identity (from/to/kind) and call site.
 * Non-graph metadata such as `generatedAt` is ignored so the result only reflects source changes.
 * All output lists are sorted to keep serialized diffs stable.
 */
object GraphDiffer {
    fun diff(baseline: DependencyGraph, current: DependencyGraph): GraphDiff {
        val baselineNodes = baseline.nodes.associateBy { it.id }
        val currentNodes = current.nodes.associateBy { it.id }
        val baselineEdges = baseline.edges.associateBy { edgeKey(it) }
        val currentEdges = current.edges.associateBy { edgeKey(it) }

        return GraphDiff(
            addedNodes = nodesIn(currentNodes).filter { it.id !in baselineNodes },
            removedNodes = nodesIn(baselineNodes).filter { it.id !in currentNodes },
            addedEdges = edgesIn(currentEdges).filter { edgeKey(it) !in baselineEdges },
            removedEdges = edgesIn(baselineEdges).filter { edgeKey(it) !in currentEdges },
            edgeDeltas = edgeDeltas(baselineEdges, currentEdges),
        )
    }

    private fun nodesIn(nodes: Map<String, GraphNode>): List<GraphNode> =
        nodes.values.sortedWith(compareBy({ it.module }, { it.sourceSet }, { it.id }))

    private fun edgesIn(edges: Map<EdgeKey, GraphEdge>): List<GraphEdge> =
        edges.entries.sortedWith(compareBy({ it.key.from }, { it.key.to }, { it.key.kind.name })).map { it.value }

    private fun edgeDeltas(
        baseline: Map<EdgeKey, GraphEdge>,
        current: Map<EdgeKey, GraphEdge>,
    ): List<GraphEdgeDelta> =
        current.keys.intersect(baseline.keys)
            .mapNotNull { key ->
                val added = current.getValue(key).callSites - baseline.getValue(key).callSites.toSet()
                val removed = baseline.getValue(key).callSites - current.getValue(key).callSites.toSet()
                if (added.isEmpty() && removed.isEmpty()) {
                    null
                } else {
                    GraphEdgeDelta(
                        from = key.from,
                        to = key.to,
                        kind = key.kind,
                        addedCallSites = added.sortedWith(callSiteOrder),
                        removedCallSites = removed.sortedWith(callSiteOrder),
                    )
                }
            }
            .sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }))

    private fun edgeKey(edge: GraphEdge) = EdgeKey(edge.from, edge.to, edge.kind)

    private val callSiteOrder = compareBy<CallSite>({ it.file }, { it.line }, { it.column })

    private data class EdgeKey(val from: String, val to: String, val kind: EdgeKind)
}
