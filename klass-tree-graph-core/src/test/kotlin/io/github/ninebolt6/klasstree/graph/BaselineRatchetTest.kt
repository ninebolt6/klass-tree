package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineRatchetTest {
    private val source = node("app:main:Main.kt#CLASS:a.Main", "a.Main", "a")
    private val target = node("legacy:main:Old.kt#CLASS:legacy.Old", "legacy.Old", "legacy")

    @Test
    fun `baseline serialization is sorted portable and deterministic`() {
        val first = graph(listOf(edge(2), edge(1).copy(kind = EdgeKind.TYPE_REFERENCE)))
        val reordered =
            first.copy(
                nodes = first.nodes.reversed(),
                edges = first.edges.reversed(),
                generatedAt = "later",
                projectRoot = "/other",
            )
        val json = render(BaselineRatchet.baseline(first))

        assertEquals(json, render(BaselineRatchet.baseline(reordered)))
        val excluded = listOf("generatedAt", "projectRoot", "nodes", "line", "column", "/checkout")
        assertFalse(excluded.any { "\"$it\"" in json })
        val baseline = KlassTreeJson.decodeFromString(DependencyBaseline.serializer(), json)
        assertEquals(listOf(EdgeKind.FUNCTION_CALL, EdgeKind.TYPE_REFERENCE), baseline.edges.map { it.kind })
        assertEquals(target.id, baseline.edges.first().to)
        assertEquals("legacy.Old", baseline.edges.first().toFqName)
        assertEquals("legacy", baseline.edges.first().toPackage)
    }

    @Test
    fun `line shifts and node locations do not change the baseline or counts`() {
        val original = graph(listOf(edge(2)))
        val shifted =
            original.copy(nodes = original.nodes.map { it.copy(line = 99, column = 8) }, edges = listOf(edge(2, 100)))
        val baseline = BaselineRatchet.baseline(original)

        assertEquals(render(baseline), render(BaselineRatchet.baseline(shifted)))
        assertEquals(
            BaselineComparison(emptyList(), emptyList(), emptyList()),
            BaselineRatchet.compare(baseline, shifted, listOf("legacy")),
        )
    }

    @Test
    fun `new edges and increased counts violate denied targets`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(1))))
        val current = graph(listOf(edge(2), edge(1).copy(kind = EdgeKind.TYPE_REFERENCE)))
        val result = BaselineRatchet.compare(baseline, current, listOf("legacy.Old"))

        assertEquals(listOf(1 to 2, 0 to 1), result.violations.map { it.baseline to it.current })
        assertEquals(result.changes, result.violations)
        assertTrue(result.progress.isEmpty())
    }

    @Test
    fun `decreased and removed edges are progress even when the target disappears`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(3), edge(2).copy(kind = EdgeKind.TYPE_REFERENCE))))
        val reduced = BaselineRatchet.compare(baseline, graph(listOf(edge(1))), listOf("legacy"))
        assertEquals(listOf(3 to 1, 2 to 0), reduced.progress.map { it.baseline to it.current })
        assertTrue(reduced.violations.isEmpty())

        val withoutTarget = graph(emptyList()).copy(nodes = listOf(source))
        val removed = BaselineRatchet.compare(baseline, withoutTarget, listOf("legacy.Old"))
        assertEquals(listOf(3 to 0, 2 to 0), removed.progress.map { it.baseline to it.current })
    }

    @Test
    fun `stored package matches missing targets without an fqName`() {
        val withoutName = graph(listOf(edge(1))).copy(nodes = listOf(source, target.copy(fqName = null)))
        val baseline = BaselineRatchet.baseline(withoutName)
        val result = BaselineRatchet.compare(baseline, graph(emptyList()).copy(nodes = emptyList()), listOf("legacy"))
        assertEquals(1, result.progress.size)
    }

    @Test
    fun `all changes are reported even when targets are not denied`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(1))))
        val result = BaselineRatchet.compare(baseline, graph(listOf(edge(2))), emptyList())
        assertEquals(1, result.changes.size)
        assertTrue(result.violations.isEmpty())
        assertTrue(result.progress.isEmpty())
    }

    @Test
    fun `matching uses exact or dotted prefixes of names and packages`() {
        val current = graph(listOf(edge(1)))
        val baseline = BaselineRatchet.baseline(graph(emptyList()))
        listOf("legacy", "legacy.Old").forEach {
            assertEquals(1, BaselineRatchet.compare(baseline, current, listOf(it)).violations.size)
        }
        listOf("leg", "legacy.O", "legacy.Old.Member").forEach {
            assertTrue(BaselineRatchet.compare(baseline, current, listOf(it)).violations.isEmpty())
        }
        val nestedTarget = target.copy(fqName = "legacy.Old.Member", packageName = "legacy.internal")
        val nested = current.copy(nodes = listOf(source, nestedTarget))
        assertEquals(1, BaselineRatchet.compare(baseline, nested, listOf("legacy.Old")).violations.size)
        assertEquals(1, BaselineRatchet.compare(baseline, nested, listOf("legacy.internal")).violations.size)
        val userIndex = current.copy(nodes = listOf(source, target.copy(fqName = "legacy.UserIndex")))
        assertTrue(BaselineRatchet.compare(baseline, userIndex, listOf("legacy.User")).violations.isEmpty())
    }

    @Test
    fun `comparison serialization is independent of graph and baseline ordering`() {
        val before = graph(listOf(edge(2), edge(1).copy(kind = EdgeKind.TYPE_REFERENCE)))
        val after = graph(listOf(edge(1), edge(3).copy(kind = EdgeKind.TYPE_REFERENCE)))
        val baseline = BaselineRatchet.baseline(before)
        val first = BaselineRatchet.compare(baseline, after, listOf("legacy"))
        val reordered =
            BaselineRatchet.compare(
                baseline.copy(edges = baseline.edges.reversed()),
                after.copy(edges = after.edges.reversed()),
                listOf("legacy"),
            )
        assertEquals(
            KlassTreeJson.encodeToString(BaselineComparison.serializer(), first),
            KlassTreeJson.encodeToString(BaselineComparison.serializer(), reordered),
        )
    }

    @Test
    fun `empty graphs compare without changes`() {
        val empty = graph(emptyList()).copy(nodes = emptyList())
        assertEquals("{\n    \"schemaVersion\": 1,\n    \"edges\": []\n}", render(BaselineRatchet.baseline(empty)))
        assertTrue(BaselineRatchet.compare(BaselineRatchet.baseline(empty), empty, listOf("legacy")).changes.isEmpty())
    }

    @Test
    fun `invalid schemas counts duplicate edges and old graphs are rejected`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(1))))
        assertFailsWith<IllegalArgumentException> { DependencyBaseline(schemaVersion = 2, edges = emptyList()) }
        assertFailsWith<IllegalArgumentException> { baseline.edges.single().copy(callSites = -1) }
        assertFailsWith<IllegalArgumentException> { baseline.copy(edges = baseline.edges + baseline.edges) }
        assertFailsWith<IllegalArgumentException> {
            KlassTreeJson.decodeFromString(DependencyBaseline.serializer(), JsonExporter.render(graph(emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            KlassTreeJson.decodeFromString(DependencyBaseline.serializer(), "{}")
        }
    }

    private fun render(baseline: DependencyBaseline): String = KlassTreeJson.encodeToString(baseline)

    private fun graph(edges: List<GraphEdge>) =
        DependencyGraph(generatedAt = "now", projectRoot = "/checkout", nodes = listOf(source, target), edges = edges)

    private fun edge(
        count: Int,
        start: Int = 1,
    ) = GraphEdge(source.id, target.id, EdgeKind.FUNCTION_CALL, List(count) { CallSite("Main.kt", start + it, 1) })

    private fun node(
        id: String,
        fqName: String,
        packageName: String,
    ) = GraphNode(
        id = id,
        kind = NodeKind.CLASS,
        name = fqName.substringAfterLast('.'),
        fqName = fqName,
        module = "app",
        sourceSet = "main",
        packageName = packageName,
        sourceFile = "Main.kt",
        line = 1,
        column = 1,
    )
}
