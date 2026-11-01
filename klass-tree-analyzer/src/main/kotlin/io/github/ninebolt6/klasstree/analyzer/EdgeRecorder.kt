package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.PsiElement
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphNode

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
