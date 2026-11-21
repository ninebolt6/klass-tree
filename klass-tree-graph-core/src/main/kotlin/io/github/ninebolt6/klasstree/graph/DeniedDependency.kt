package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

@Serializable
data class DeniedDependency(
    val from: String? = null,
    val to: String,
) : JavaSerializable
