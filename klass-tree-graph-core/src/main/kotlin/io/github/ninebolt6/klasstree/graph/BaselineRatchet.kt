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

        fun deniedNow(change: EdgeCountChange): Boolean {
            val node = nodes[change.to] ?: return deniedInBaseline(change, previous, deniedTargets)
            return deniedTargets.any { node.fqName.matchesTarget(it) || node.packageName.matchesTarget(it) }
        }
        return BaselineComparison(
            changes = changes,
            violations = changes.filter { it.current > it.baseline && deniedNow(it) },
            progress =
                changes.filter {
                    it.current < it.baseline && (deniedNow(it) || deniedInBaseline(it, previous, deniedTargets))
                },
        )
    }

    private fun deniedInBaseline(
        change: EdgeCountChange,
        previous: Map<Triple<String, String, EdgeKind>, BaselineEdge>,
        deniedTargets: List<String>,
    ): Boolean {
        val old = previous[Triple(change.from, change.to, change.kind)] ?: return false
        return deniedTargets.any { old.toFqName.matchesTarget(it) || old.toPackage.matchesTarget(it) }
    }

    private fun String?.matchesTarget(prefix: String): Boolean = this == prefix || this?.startsWith("$prefix.") == true
}
