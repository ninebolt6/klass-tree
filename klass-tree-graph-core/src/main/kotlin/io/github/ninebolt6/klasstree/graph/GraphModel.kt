package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The first version aggregates members into their owning class (or file scope for top-level
 * declarations). Declaration-level nodes can be added later without changing the JSON schema.
 */
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

/**
 * [id] is stable within one analysis snapshot. It is intentionally not just an FQ name: the
 * module/source set, source path and declaration kind are included so that nested and same-named
 * declarations do not collapse into one node.
 */
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

/** A directed dependency edge: `from` depends on `to`. */
@Serializable
data class GraphEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val callSites: List<CallSite> = emptyList(),
)

@Serializable
data class DependencyGraph(
    val schemaVersion: Int = 2,
    val generator: String = "klass-tree",
    val generatedAt: String,
    val projectRoot: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)
