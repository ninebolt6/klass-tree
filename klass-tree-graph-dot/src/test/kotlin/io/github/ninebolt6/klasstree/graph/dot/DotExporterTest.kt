package io.github.ninebolt6.klasstree.graph.dot

import io.github.ninebolt6.klasstree.graph.CallSite
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphEdge
import io.github.ninebolt6.klasstree.graph.GraphNode
import io.github.ninebolt6.klasstree.graph.NodeKind
import kotlin.test.Test
import kotlin.test.assertTrue

class DotExporterTest {
    private val graph = DependencyGraph(
        generatedAt = "2026-01-01T00:00:00Z",
        projectRoot = "/project",
        nodes = listOf(
            GraphNode(
                id = "shared:src/User.kt#CLASS:com.example.User",
                kind = NodeKind.CLASS,
                name = "User",
                fqName = "com.example.User",
                module = "shared",
                sourceSet = "main",
                packageName = "com.example",
                sourceFile = "shared/src/User.kt",
                line = 3,
                column = 7,
            ),
            GraphNode(
                id = "shared:src/Repo.kt#INTERFACE:com.example.Repo",
                kind = NodeKind.INTERFACE,
                name = "Repo",
                fqName = "com.example.Repo",
                module = "shared",
                sourceSet = "main",
                packageName = "com.example",
                sourceFile = "shared/src/Repo.kt",
                line = 1,
                column = 11,
            ),
        ),
        edges = listOf(
            GraphEdge(
                from = "shared:src/User.kt#CLASS:com.example.User",
                to = "shared:src/Repo.kt#INTERFACE:com.example.Repo",
                kind = EdgeKind.TYPE_REFERENCE,
                callSites = listOf(CallSite("shared/src/User.kt", 5, 12)),
            ),
        ),
    )

    @Test
    fun `dot groups nodes by module`() {
        val dot = DotExporter.render(graph)

        assertTrue(dot.startsWith("digraph klass_tree {"))
        assertTrue(dot.contains("subgraph cluster_shared {"))
        assertTrue(dot.contains("\"shared:src/User.kt#CLASS:com.example.User\""))
        assertTrue(dot.contains("label=\"TYPE_REFERENCE\""))
        assertTrue(dot.trimEnd().endsWith("}"))
    }

    @Test
    fun `dot keeps modules separate when sanitized cluster names collide`() {
        val nodes = listOf("a-b", "a_b").map { module ->
            GraphNode(
                id = "$module:src/X.kt#CLASS:p.X$module",
                kind = NodeKind.CLASS,
                name = "X",
                module = module,
                sourceSet = "main",
            )
        }
        val dot = DotExporter.render(graph.copy(nodes = nodes, edges = emptyList()))

        assertTrue(dot.contains("subgraph cluster_a_b {"))
        assertTrue(dot.contains("subgraph cluster_a_b_2 {"))
        assertTrue(Regex("subgraph cluster_").findAll(dot).count() == 2)
    }

    @Test
    fun `dot escapes quotes and backslashes`() {
        val node = GraphNode(
            id = "id\"with\\special",
            kind = NodeKind.CLASS,
            name = "A\"B\\C",
            module = "m",
            sourceSet = "main",
        )
        val dot = DotExporter.render(graph.copy(nodes = listOf(node), edges = emptyList()))

        assertTrue(dot.contains("id\\\"with\\\\special"))
        assertTrue(dot.contains("A\\\"B\\\\C"))
    }
}
