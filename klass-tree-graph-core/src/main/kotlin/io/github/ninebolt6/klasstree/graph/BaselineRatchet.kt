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
                            fromFqName = nodes[edge.from]?.fqName,
                            fromPackage = nodes[edge.from]?.packageName,
                            toFqName = nodes[edge.to]?.fqName,
                            toPackage = nodes[edge.to]?.packageName,
                        )
                    },
        )
    }

    fun compare(
        baseline: DependencyBaseline,
        current: DependencyGraph,
        rules: List<DeniedDependency>,
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
                        nodes.getValue(change.to).let { target ->
                            val source = nodes.getValue(change.from)
                            rules.any {
                                it.from.matches(source.fqName, source.packageName) &&
                                    it.to.matches(target.fqName, target.packageName)
                            }
                        }
                },
            progress =
                changes.filter { change ->
                    change.current < change.baseline &&
                        previous.getValue(Triple(change.from, change.to, change.kind)).let { edge ->
                            rules.any {
                                it.from.matches(edge.fromFqName, edge.fromPackage) &&
                                    it.to.matches(edge.toFqName, edge.toPackage)
                            }
                        }
                },
        )
    }

    private fun String?.matches(
        fqName: String?,
        packageName: String?,
    ): Boolean = this == null || fqName.matchesTarget(this) || packageName.matchesTarget(this)

    private fun String?.matchesTarget(prefix: String): Boolean = this == prefix || this?.startsWith("$prefix.") == true
}
