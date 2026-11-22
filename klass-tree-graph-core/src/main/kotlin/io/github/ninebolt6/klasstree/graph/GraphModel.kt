package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    ANNOTATION,
    TYPE_ALIAS,
    FILE_SCOPE,
}

@Serializable
enum class EdgeKind {
    TYPE_REFERENCE,
    INHERITANCE,
    CONSTRUCTOR_CALL,
    FUNCTION_CALL,
    PROPERTY_ACCESS,
    CALLABLE_REFERENCE,
    ANNOTATION,
    OPERATOR_OR_SYNTHETIC_CALL,
}

@Serializable
data class GraphNode(
    val id: String,
    val kind: NodeKind,
    val name: String,
    val fqName: String? = null,
    val module: String,
    val sourceSet: String,
    @SerialName("package") val packageName: String? = null,
    val sourceFile: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/** A source location where a dependency is used. Lines and columns are 1-based. */
@Serializable
data class CallSite(
    val file: String,
    val line: Int,
    val column: Int,
)

@Serializable
data class GraphEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val callSites: List<CallSite> = emptyList(),
)

@Serializable
data class DependencyGraph(
    val schemaVersion: Int = 1,
    val generator: String = "klass-tree",
    val generatedAt: String,
    val projectRoot: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)
