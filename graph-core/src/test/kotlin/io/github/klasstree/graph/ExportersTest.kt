package io.github.klasstree.graph

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportersTest {
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
        stats = GraphStats(
            nodeCount = 2,
            edgeCount = 1,
            callSiteCount = 1,
            sourceFileCount = 2,
            elapsedMillis = 5,
        ),
    )

    @Test
    fun `json round trips through the public schema`() {
        val json = JsonExporter.render(graph)
        val decoded = KlassTreeJson.decodeFromString<DependencyGraph>(json)

        assertEquals(graph, decoded)
        assertTrue(json.contains("\"package\": \"com.example\""))
        assertTrue(!json.contains("null"))
    }

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
