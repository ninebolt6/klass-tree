package io.github.ninebolt6.klasstree.graph

private data class EdgeKey(
    val from: String,
    val to: String,
    val kind: EdgeKind,
)

/**
 * Accumulates nodes and edges, merging repeated dependencies of the same kind into one edge with
 * multiple call sites.
 */
class GraphBuilder {
    private val nodes = LinkedHashMap<String, GraphNode>()
    private val edges = LinkedHashMap<EdgeKey, MutableSet<CallSite>>()

    fun addNode(node: GraphNode) {
        val previous = nodes.putIfAbsent(node.id, node)
        check(previous == null || previous == node) {
            "Node id collision: ${node.id} (${previous?.sourceFile} vs ${node.sourceFile})"
        }
    }

    fun addEdge(
        from: String,
        to: String,
        kind: EdgeKind,
        callSite: CallSite,
    ) {
        edges.getOrPut(EdgeKey(from, to, kind)) { LinkedHashSet() }.add(callSite)
    }

    fun build(
        generatedAt: String,
        projectRoot: String,
    ): DependencyGraph {
        val sortedNodes = nodes.values.sortedWith(compareBy({ it.module }, { it.sourceSet }, { it.id }))
        val sortedEdges =
            edges.entries
                .sortedWith(compareBy({ it.key.from }, { it.key.to }, { it.key.kind.name }))
                .map { (key, sites) ->
                    GraphEdge(
                        from = key.from,
                        to = key.to,
                        kind = key.kind,
                        callSites = sites.sortedWith(compareBy({ it.file }, { it.line }, { it.column })),
                    )
                }
        return DependencyGraph(
            generatedAt = generatedAt,
            projectRoot = projectRoot,
            nodes = sortedNodes,
            edges = sortedEdges,
        )
    }
}
