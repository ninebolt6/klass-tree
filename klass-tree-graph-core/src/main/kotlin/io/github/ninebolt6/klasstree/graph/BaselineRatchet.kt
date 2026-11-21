package io.github.ninebolt6.klasstree.graph

object BaselineRatchet {
    fun baseline(graph: DependencyGraph): DependencyBaseline {
        val nodes = graph.nodes.associateBy { it.id }
        return DependencyBaseline(
            edges =
                graph.edges
                    .sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }))
                    .map { edge ->
                        BaselineEdge(
                            from = edge.from,
                            to = edge.to,
                            kind = edge.kind,
                            callSites = edge.callSites.size,
                            toFqName = nodes[edge.to]?.fqName,
                            toPackage = nodes[edge.to]?.packageName,
                        )
                    },
        )
    }

    fun compare(
        baseline: DependencyBaseline,
        current: DependencyGraph,
        deniedTargets: List<String>,
    ): BaselineComparison {
        val previous = baseline.edges.associateBy { Triple(it.from, it.to, it.kind) }
        val present = current.edges.associateBy { Triple(it.from, it.to, it.kind) }
        val nodes = current.nodes.associateBy { it.id }
        val changes =
            (previous.keys + present.keys)
                .mapNotNull { key ->
                    val before = previous[key]?.callSites ?: 0
                    val after = present[key]?.callSites?.size ?: 0
                    if (before == after) null else EdgeCountChange(key.first, key.second, key.third, before, after)
                }.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }))

        return BaselineComparison(
            changes = changes,
            violations =
                changes.filter { change ->
                    change.current > change.baseline &&
                        nodes.getValue(change.to).let { node ->
                            deniedTargets.any { node.fqName.matchesTarget(it) || node.packageName.matchesTarget(it) }
                        }
                },
            progress =
                changes.filter { change ->
                    change.current < change.baseline &&
                        previous.getValue(Triple(change.from, change.to, change.kind)).let { edge ->
                            deniedTargets.any { edge.toFqName.matchesTarget(it) || edge.toPackage.matchesTarget(it) }
                        }
                },
        )
    }

    private fun String?.matchesTarget(prefix: String): Boolean = this == prefix || this?.startsWith("$prefix.") == true
}
