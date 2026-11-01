package io.github.klasstree.analyzer

import com.intellij.psi.PsiElement
import io.github.klasstree.graph.EdgeKind
import io.github.klasstree.graph.GraphNode

/**
 * Records an edge to a resolved source declaration: finds the owning node of the reference site,
 * drops self-edges, attaches the call site, and updates the resolution counters. Shared by the
 * Kotlin and Java candidate resolvers.
 */
internal class EdgeRecorder(
    private val index: DeclarationIndex,
    private val stats: MutableResolutionStats,
) {
    fun record(to: GraphNode, element: PsiElement, kind: EdgeKind) {
        val from = index.nodeForElement(element)
        if (from == null) {
            stats.skipped++
            return
        }
        stats.resolvedSource++
        if (from.id == to.id) {
            stats.selfEdgesSkipped++
            return
        }
        val callSite = index.callSite(element)
        if (callSite == null) {
            stats.skipped++
            return
        }
        index.addEdge(from, to, kind, callSite)
    }
}
