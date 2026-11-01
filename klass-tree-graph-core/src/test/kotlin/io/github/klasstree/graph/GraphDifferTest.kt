package io.github.klasstree.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphDifferTest {
    private fun node(id: String, module: String = "app") = GraphNode(
        id = id,
        kind = NodeKind.CLASS,
        name = id.substringAfterLast("."),
        fqName = id,
        module = module,
        sourceSet = "main",
        packageName = id.substringBeforeLast(".", ""),
    )

    private fun edge(from: String, to: String, kind: EdgeKind, vararg sites: CallSite) =
        GraphEdge(from = from, to = to, kind = kind, callSites = sites.toList())

    private fun site(line: Int) = CallSite("Api.kt", line, 1)

    private fun graph(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        generatedAt: String = "t0",
    ) = DependencyGraph(
        generatedAt = generatedAt,
        projectRoot = "/p",
        nodes = nodes,
        edges = edges,
        stats = GraphStats(nodes.size, edges.size, edges.sumOf { it.callSites.size }, 1, 0),
    )

    private val a = node("a.A")
    private val b = node("b.B")
    private val c = node("c.C")

    @Test
    fun `identical graphs produce an empty diff`() {
        val baseline = graph(listOf(a, b), listOf(edge(a.id, b.id, EdgeKind.FUNCTION_CALL, site(3))))
        val diff = GraphDiffer.diff(baseline, baseline.copy(generatedAt = "t9"))

        assertFalse(diff.hasChanges)
    }

    @Test
    fun `added and removed nodes and edges are reported`() {
        val baseline = graph(
            listOf(a, b, c),
            listOf(
                edge(a.id, b.id, EdgeKind.FUNCTION_CALL),
                edge(b.id, c.id, EdgeKind.TYPE_REFERENCE),
            ),
        )
        val current = graph(
            listOf(a, b),
            listOf(
                edge(a.id, c.id, EdgeKind.CONSTRUCTOR_CALL),
            ),
        )

        val diff = GraphDiffer.diff(baseline, current)

        assertEquals(listOf("c.C"), diff.removedNodes.map { it.fqName })
        assertTrue(diff.addedNodes.isEmpty())
        assertEquals(
            listOf(Triple(a.id, c.id, EdgeKind.CONSTRUCTOR_CALL)),
            diff.addedEdges.map { Triple(it.from, it.to, it.kind) },
        )
        assertEquals(
            setOf(
                Triple(a.id, b.id, EdgeKind.FUNCTION_CALL),
                Triple(b.id, c.id, EdgeKind.TYPE_REFERENCE),
            ),
            diff.removedEdges.map { Triple(it.from, it.to, it.kind) }.toSet(),
        )
    }

    @Test
    fun `call site changes on surviving edges become deltas`() {
        val baseline = graph(
            listOf(a, b),
            listOf(edge(a.id, b.id, EdgeKind.FUNCTION_CALL, site(3), site(7))),
        )
        val current = graph(
            listOf(a, b),
            listOf(edge(a.id, b.id, EdgeKind.FUNCTION_CALL, site(3), site(9))),
        )

        val diff = GraphDiffer.diff(baseline, current)

        assertTrue(diff.addedEdges.isEmpty() && diff.removedEdges.isEmpty())
        assertEquals(1, diff.edgeDeltas.size)
        val delta = diff.edgeDeltas.single()
        assertEquals(listOf(site(9)), delta.addedCallSites)
        assertEquals(listOf(site(7)), delta.removedCallSites)
    }

    @Test
    fun `diff output is deterministic regardless of input order`() {
        val baseline = graph(
            listOf(b, a, c),
            listOf(edge(b.id, c.id, EdgeKind.TYPE_REFERENCE), edge(a.id, b.id, EdgeKind.FUNCTION_CALL)),
        )
        val current = graph(
            listOf(c, a),
            listOf(edge(a.id, c.id, EdgeKind.CONSTRUCTOR_CALL)),
        )

        val first = GraphDiffer.diff(baseline, current)
        val second = GraphDiffer.diff(current.copy(nodes = current.nodes.reversed()), baseline)

        assertEquals(
            listOf("b.B"),
            first.removedNodes.map { it.fqName },
        )
        assertEquals(first.addedEdges.map { it.kind }, second.removedEdges.map { it.kind })
    }

    @Test
    fun `stats differences do not produce changes`() {
        val baseline = graph(listOf(a), emptyList())
        val current = baseline.copy(
            stats = GraphStats(99, 99, 99, 99, 99, ResolutionStats(candidates = 5)),
        )

        assertFalse(GraphDiffer.diff(baseline, current).hasChanges)
    }
}
