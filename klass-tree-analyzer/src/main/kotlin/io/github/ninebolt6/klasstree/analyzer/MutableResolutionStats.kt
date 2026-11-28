package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.PsiElement
import io.github.ninebolt6.klasstree.graph.EdgeKind

internal class MutableResolutionStats {
    var candidates: Int = 0
    var resolvedSource: Int = 0
    var resolvedExternal: Int = 0
    var unresolved: Int = 0
    var skipped: Int = 0
    var selfEdgesSkipped: Int = 0
    val analysisErrors = mutableListOf<String>()
    private val unresolvedByKind = sortedMapOf<String, Int>()
    private val unresolvedSamples = mutableListOf<UnresolvedReferenceSample>()

    fun recordUnresolved(
        kind: EdgeKind,
        element: PsiElement,
        index: DeclarationIndex,
    ) {
        unresolved++
        unresolvedByKind[kind.name] = (unresolvedByKind[kind.name] ?: 0) + 1
        if (unresolvedSamples.size < MAX_UNRESOLVED_SAMPLES) {
            val site = index.callSite(element)
            unresolvedSamples +=
                UnresolvedReferenceSample(
                    kind = kind.name,
                    file = site?.file,
                    line = site?.line,
                    column = site?.column,
                    text = element.text.replace(Regex("\\s+"), " ").take(MAX_SAMPLE_TEXT_LENGTH),
                )
        }
    }

    fun toImmutable(): ResolutionStats =
        ResolutionStats(
            candidates = candidates,
            resolvedSource = resolvedSource,
            resolvedExternal = resolvedExternal,
            unresolved = unresolved,
            skipped = skipped,
            selfEdgesSkipped = selfEdgesSkipped,
            analysisErrors = analysisErrors.toList(),
            unresolvedByKind = unresolvedByKind.toMap(),
            unresolvedSamples = unresolvedSamples.toList(),
        )

    private companion object {
        const val MAX_UNRESOLVED_SAMPLES = 200
        const val MAX_SAMPLE_TEXT_LENGTH = 160
    }
}
