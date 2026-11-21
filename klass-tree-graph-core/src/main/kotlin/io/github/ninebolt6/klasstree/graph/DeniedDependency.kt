package io.github.ninebolt6.klasstree.graph

import java.io.Serializable

data class DeniedDependency(
    val from: String? = null,
    val to: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
