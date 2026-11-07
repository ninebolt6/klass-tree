package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.PsiElement
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphNode

internal class EdgeRecorder(
    private val index: DeclarationIndex,
    private val stats: MutableResolutionStats,
) {
    fun record(
        to: GraphNode,
        element: PsiElement,
        kind: EdgeKind,
    ) {
        val from = index.nodeForElement(element)
        when {
            from == null -> {
                stats.skipped++
            }

            from.id == to.id -> {
                stats.resolvedSource++
                stats.selfEdgesSkipped++
            }

            else -> {
                stats.resolvedSource++
                val callSite = index.callSite(element)
                if (callSite == null) {
                    stats.skipped++
                } else {
                    index.addEdge(from, to, kind, callSite)
                }
            }
        }
    }
}
