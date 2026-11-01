package io.github.ninebolt6.klasstree.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphBuilderTest {
    private fun node(id: String, kind: NodeKind = NodeKind.CLASS): GraphNode = GraphNode(
        id = id,
        kind = kind,
        name = id.substringAfterLast('.'),
        fqName = id,
        module = "shared",
        sourceSet = "main",
        sourceFile = "shared/src/main/kotlin/$id.kt",
    )

    @Test
    fun `merges repeated edges and keeps all call sites`() {
        val builder = GraphBuilder()
        val service = node("com.example.Service")
        val repository = node("com.example.Repository", NodeKind.INTERFACE)
        builder.addNode(service)
        builder.addNode(repository)

        builder.addEdge(
            service.id,
            repository.id,
            EdgeKind.FUNCTION_CALL,
            CallSite("Service.kt", 10, 5),
        )
        builder.addEdge(
            service.id,
            repository.id,
            EdgeKind.FUNCTION_CALL,
            CallSite("Service.kt", 12, 9),
        )
        builder.addEdge(
            service.id,
            repository.id,
            EdgeKind.TYPE_REFERENCE,
            CallSite("Service.kt", 3, 20),
        )

        val graph = builder.build(
            generatedAt = "2026-01-01T00:00:00Z",
            projectRoot = "/project",
        )

        assertEquals(2, graph.nodes.size)
        assertEquals(2, graph.edges.size)
        assertEquals(3, graph.edges.sumOf { it.callSites.size })
        assertEquals(
            listOf(CallSite("Service.kt", 10, 5), CallSite("Service.kt", 12, 9)),
            graph.edges.single { it.kind == EdgeKind.FUNCTION_CALL }.callSites,
        )
        assertTrue(graph.edges.all { it.from == service.id && it.to == repository.id })
    }

    @Test
    fun `sorts nodes and edges deterministically`() {
        val builder = GraphBuilder()
        listOf(node("z.Z"), node("a.A"), node("m.M")).forEach(builder::addNode)
        builder.addEdge("z.Z", "a.A", EdgeKind.TYPE_REFERENCE, CallSite("z.kt", 1, 1))
        builder.addEdge("a.A", "m.M", EdgeKind.INHERITANCE, CallSite("a.kt", 1, 1))

        val graph = builder.build(
            generatedAt = "2026-01-01T00:00:00Z",
            projectRoot = "/project",
        )

        assertEquals(listOf("a.A", "m.M", "z.Z"), graph.nodes.map { it.id })
        assertEquals(listOf("a.A", "z.Z"), graph.edges.map { it.from })
    }

    @Test
    fun `deduplicates and sorts call sites for one edge`() {
        val builder = GraphBuilder()
        val service = node("com.example.Service")
        val repository = node("com.example.Repository")
        builder.addNode(service)
        builder.addNode(repository)

        builder.addEdge(service.id, repository.id, EdgeKind.FUNCTION_CALL, CallSite("b.kt", 3, 1))
        builder.addEdge(service.id, repository.id, EdgeKind.FUNCTION_CALL, CallSite("a.kt", 9, 9))
        builder.addEdge(service.id, repository.id, EdgeKind.FUNCTION_CALL, CallSite("b.kt", 1, 5))
        builder.addEdge(service.id, repository.id, EdgeKind.FUNCTION_CALL, CallSite("b.kt", 1, 5))

        val graph = builder.build(
            generatedAt = "2026-01-01T00:00:00Z",
            projectRoot = "/project",
        )

        assertEquals(
            listOf(CallSite("a.kt", 9, 9), CallSite("b.kt", 1, 5), CallSite("b.kt", 3, 1)),
            graph.edges.single().callSites,
        )
    }

    @Test
    fun `rejects a node id collision with different content`() {
        val builder = GraphBuilder()
        builder.addNode(node("com.example.A"))
        assertFailsWith<IllegalStateException> {
            builder.addNode(node("com.example.A", NodeKind.OBJECT))
        }
    }
}
