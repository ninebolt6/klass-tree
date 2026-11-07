package io.github.ninebolt6.klasstree.analyzer

internal class MutableResolutionStats {
    var candidates: Int = 0
    var resolvedSource: Int = 0
    var resolvedExternal: Int = 0
    var unresolved: Int = 0
    var skipped: Int = 0
    var selfEdgesSkipped: Int = 0
    val analysisErrors = mutableListOf<String>()

    fun toImmutable(): ResolutionStats =
        ResolutionStats(
            candidates = candidates,
            resolvedSource = resolvedSource,
            resolvedExternal = resolvedExternal,
            unresolved = unresolved,
            skipped = skipped,
            selfEdgesSkipped = selfEdgesSkipped,
            analysisErrors = analysisErrors.toList(),
        )
}
