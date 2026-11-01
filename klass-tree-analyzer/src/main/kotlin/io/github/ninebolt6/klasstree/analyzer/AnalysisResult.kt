package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.DependencyGraph

data class AnalysisResult(
    val graph: DependencyGraph,
    val stats: GraphStats,
)

data class GraphStats(
    val nodeCount: Int,
    val edgeCount: Int,
    val callSiteCount: Int,
    val sourceFileCount: Int,
    val elapsedMillis: Long,
    val resolution: ResolutionStats = ResolutionStats(),
)

data class ResolutionStats(
    val candidates: Int = 0,
    val resolvedSource: Int = 0,
    val resolvedExternal: Int = 0,
    val unresolved: Int = 0,
    val skipped: Int = 0,
    val selfEdgesSkipped: Int = 0,
    val analysisErrors: List<String> = emptyList(),
)
