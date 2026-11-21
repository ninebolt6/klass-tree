package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable

@Serializable
data class EdgeCountChange(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val baseline: Int,
    val current: Int,
)

@Serializable
data class BaselineComparison(
    val changes: List<EdgeCountChange>,
    val violations: List<EdgeCountChange>,
    val progress: List<EdgeCountChange>,
)
