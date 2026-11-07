package io.github.ninebolt6.klasstree.graph.dot

import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphNode
import io.github.ninebolt6.klasstree.graph.NodeKind
import java.nio.file.Files
import java.nio.file.Path

object DotExporter {
    fun render(graph: DependencyGraph): String =
        buildString {
            appendLine("digraph klass_tree {")
            appendLine("  rankdir=LR;")
            appendLine("  graph [fontname=\"Helvetica\", compound=true];")
            appendLine(
                "  node [shape=box, style=\"rounded,filled\", fontname=\"Helvetica\", " +
                    "fontsize=10, fillcolor=\"#ffffff\"];",
            )
            appendLine("  edge [fontname=\"Helvetica\", fontsize=8];")
            appendLine()

            val byModule = graph.nodes.groupBy { it.module }
            val usedClusterIds = HashSet<String>()
            byModule.toSortedMap().forEach { (module, moduleNodes) ->
                appendLine("  subgraph ${uniqueClusterId(module, usedClusterIds)} {")
                appendLine("    label=${quote(module)};")
                appendLine("    style=\"rounded,dashed\";")
                appendLine("    color=\"#b0b0b0\";")
                moduleNodes.sortedBy { it.id }.forEach { node ->
                    append("    ").append(quote(node.id))
                    append(" [label=").append(quote(nodeLabel(node)))
                    append(", shape=").append(nodeShape(node.kind))
                    append(", fillcolor=").append(quote(nodeColor(node.kind)))
                    appendLine("];")
                }
                appendLine("  }")
            }

            if (graph.edges.isNotEmpty()) appendLine()
            graph.edges.forEach { edge ->
                append("  ").append(quote(edge.from))
                append(" -> ").append(quote(edge.to))
                append(" [label=").append(quote(edge.kind.name))
                append(", color=").append(quote(edgeColor(edge.kind)))
                if (edge.callSites.isNotEmpty()) {
                    val first = edge.callSites.first()
                    append(", tooltip=").append(quote("${first.file}:${first.line} (${edge.callSites.size} site(s))"))
                }
                appendLine("];")
            }

            appendLine("}")
        }

    fun write(
        graph: DependencyGraph,
        target: Path,
    ) {
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, render(graph))
    }

    private fun nodeLabel(node: GraphNode): String =
        buildString {
            append(node.name)
            val detail = node.fqName?.takeIf { it.isNotBlank() && it != node.name }
            if (detail != null) append("\n").append(detail)
            val location = node.sourceFile?.let { "$it${node.line?.let { line -> ":$line" } ?: ""}" }
            if (location != null) append("\n").append(location)
        }

    private fun nodeShape(kind: NodeKind): String =
        when (kind) {
            NodeKind.INTERFACE -> "ellipse"
            NodeKind.OBJECT, NodeKind.ANNOTATION -> "hexagon"
            NodeKind.ENUM -> "diamond"
            NodeKind.TYPE_ALIAS -> "note"
            NodeKind.FILE_SCOPE -> "folder"
            NodeKind.CLASS -> "box"
        }

    private fun nodeColor(kind: NodeKind): String =
        when (kind) {
            NodeKind.CLASS -> "#dbeafe"
            NodeKind.INTERFACE -> "#dcfce7"
            NodeKind.OBJECT -> "#fef3c7"
            NodeKind.ENUM -> "#fce7f3"
            NodeKind.ANNOTATION -> "#ede9fe"
            NodeKind.TYPE_ALIAS -> "#f3f4f6"
            NodeKind.FILE_SCOPE -> "#e5e7eb"
        }

    private fun edgeColor(kind: EdgeKind): String =
        when (kind) {
            EdgeKind.TYPE_REFERENCE -> "#3b82f6"
            EdgeKind.INHERITANCE -> "#16a34a"
            EdgeKind.CONSTRUCTOR_CALL -> "#f59e0b"
            EdgeKind.FUNCTION_CALL -> "#dc2626"
            EdgeKind.PROPERTY_ACCESS -> "#7c3aed"
            EdgeKind.CALLABLE_REFERENCE -> "#0891b2"
            EdgeKind.ANNOTATION -> "#a855f7"
            EdgeKind.OPERATOR_OR_SYNTHETIC_CALL -> "#6b7280"
        }

    // Sanitized module names can collide (a-b and a_b both give cluster_a_b); suffix on repeat.
    private fun uniqueClusterId(
        module: String,
        used: MutableSet<String>,
    ): String {
        val base = "cluster_" + module.replace(Regex("[^A-Za-z0-9_]"), "_")
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) candidate = "${base}_${suffix++}"
        return candidate
    }

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> {
                        append("\\\\")
                    }

                    '"' -> {
                        append("\\\"")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {}

                    else -> {
                        append(ch)
                    }
                }
            }
            append('"')
        }
}
