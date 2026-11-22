package io.github.ninebolt6.klasstree.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphDifferTest {
    private fun node(
        id: String,
        module: String = "app",
    ) = GraphNode(
        id = id,
        kind = NodeKind.CLASS,
        name = id.substringAfterLast("."),
        fqName = id,
        module = module,
        sourceSet = "main",
        packageName = id.substringBeforeLast(".", ""),
    )

    private fun edge(
        from: String,
        to: String,
        kind: EdgeKind,
        vararg sites: CallSite,
    ) = GraphEdge(from = from, to = to, kind = kind, callSites = sites.toList())

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
    fun `node and edge additions and removals are deterministic`() {
        val d = node("d.D")
        val e = node("e.E")
        val baseline =
            graph(
                listOf(a, b, c),
                listOf(
                    edge(a.id, b.id, EdgeKind.FUNCTION_CALL),
                    edge(b.id, c.id, EdgeKind.TYPE_REFERENCE),
                ),
            )
        val current =
            graph(
                listOf(a, d, e),
                listOf(
                    edge(a.id, d.id, EdgeKind.CONSTRUCTOR_CALL),
                    edge(d.id, e.id, EdgeKind.TYPE_REFERENCE),
                ),
            )

        val diff = GraphDiffer.diff(baseline, current)

        assertEquals(listOf("b.B", "c.C"), diff.removedNodes.map { it.fqName })
        assertEquals(listOf("d.D", "e.E"), diff.addedNodes.map { it.fqName })
        assertEquals(
            listOf(
                Triple(a.id, d.id, EdgeKind.CONSTRUCTOR_CALL),
                Triple(d.id, e.id, EdgeKind.TYPE_REFERENCE),
            ),
            diff.addedEdges.map { Triple(it.from, it.to, it.kind) },
        )
        assertEquals(
            setOf(
                Triple(a.id, b.id, EdgeKind.FUNCTION_CALL),
                Triple(b.id, c.id, EdgeKind.TYPE_REFERENCE),
            ),
            diff.removedEdges.map { Triple(it.from, it.to, it.kind) }.toSet(),
        )
        assertEquals(
            diff,
            GraphDiffer.diff(
                baseline.copy(nodes = baseline.nodes.reversed(), edges = baseline.edges.reversed()),
                current.copy(nodes = current.nodes.reversed(), edges = current.edges.reversed()),
            ),
        )
    }

    @Test
    fun `call site changes on surviving edges become deltas`() {
        val baseline =
            graph(
                listOf(a, b),
                listOf(edge(a.id, b.id, EdgeKind.FUNCTION_CALL, site(3), site(7))),
            )
        val current =
            graph(
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
}
