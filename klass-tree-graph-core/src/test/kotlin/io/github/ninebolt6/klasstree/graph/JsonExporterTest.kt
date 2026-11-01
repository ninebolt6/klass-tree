package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonExporterTest {
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
    fun `json round trips through the public schema`() {
        val json = JsonExporter.render(graph)
        val decoded = KlassTreeJson.decodeFromString<DependencyGraph>(json)

        assertEquals(graph, decoded)
        assertTrue(json.contains("\"package\": \"com.example\""))
        assertTrue(!json.contains("null"))
    }
}
