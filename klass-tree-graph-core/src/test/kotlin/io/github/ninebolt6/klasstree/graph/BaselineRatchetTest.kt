package io.github.ninebolt6.klasstree.graph

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineRatchetTest {
    private val source = node("app:main:Main.kt#CLASS:a.Main", "a.Main", "a")
    private val target = node("legacy:main:Old.kt#CLASS:legacy.Old", "legacy.Old", "legacy")

    @Test
    fun `baseline serialization is deterministic portable and independent of line shifts`() {
        val first = graph(listOf(edge(2), edge(1).copy(kind = EdgeKind.TYPE_REFERENCE)))
        val shifted =
            first.copy(
                nodes = first.nodes.reversed().map { it.copy(line = 99, column = 8) },
                edges =
                    first.edges.reversed().map { edge ->
                        edge.copy(callSites = edge.callSites.map { it.copy(line = it.line + 100) })
                    },
                generatedAt = "later",
                projectRoot = "/other",
            )
        val baseline = BaselineRatchet.baseline(first)
        val json = KlassTreeJson.encodeToString(baseline)

        assertEquals(json, KlassTreeJson.encodeToString(BaselineRatchet.baseline(shifted)))
        val excluded = listOf("generatedAt", "projectRoot", "nodes", "line", "column", "/checkout")
        assertFalse(excluded.any { "\"$it\"" in json })
        assertEquals(listOf(EdgeKind.FUNCTION_CALL, EdgeKind.TYPE_REFERENCE), baseline.edges.map { it.kind })
        val result = BaselineRatchet.compare(baseline, shifted, listOf(DeniedDependency(to = "legacy")))
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `new edges and increased counts violate denied targets`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(1))))
        val current = graph(listOf(edge(2), edge(1).copy(kind = EdgeKind.TYPE_REFERENCE)))
        val result = BaselineRatchet.compare(baseline, current, listOf(DeniedDependency(to = "legacy.Old")))

        assertEquals(listOf(1 to 2, 0 to 1), result.violations.map { it.baseline to it.current })
    }

    @Test
    fun `decreased and removed edges report progress from stored source and target metadata`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(3), edge(2).copy(kind = EdgeKind.TYPE_REFERENCE))))
        val rules = listOf(DeniedDependency(from = "a.Main", to = "legacy.Old"))
        val reduced = BaselineRatchet.compare(baseline, graph(listOf(edge(1))), rules)
        assertEquals(listOf(3 to 1, 2 to 0), reduced.progress.map { it.baseline to it.current })

        val packageBaseline = baseline.copy(edges = baseline.edges.map { it.copy(fromFqName = null, toFqName = null) })
        val withoutNodes = graph(emptyList()).copy(nodes = emptyList())
        val packageRules = listOf(DeniedDependency(from = "a", to = "legacy"))
        val removed = BaselineRatchet.compare(packageBaseline, withoutNodes, packageRules)
        assertEquals(listOf(3 to 0, 2 to 0), removed.progress.map { it.baseline to it.current })
    }

    @Test
    fun `moved nodes use baseline names for progress and current names for violations`() {
        val baseline = BaselineRatchet.baseline(graph(listOf(edge(3))))
        val movedTarget = target.copy(fqName = null, packageName = "fresh")
        val movedSource = source.copy(fqName = "fresh.Main", packageName = "fresh")
        val moved = graph(listOf(edge(1))).copy(nodes = listOf(movedSource, movedTarget))
        val rules = listOf(DeniedDependency(from = "a.Main", to = "legacy.Old"))
        val reduced = BaselineRatchet.compare(baseline, moved, rules)
        assertEquals(listOf(3 to 1), reduced.progress.map { it.baseline to it.current })
        val freshRules = listOf(DeniedDependency(from = "fresh", to = "fresh"))
        assertTrue(BaselineRatchet.compare(baseline, moved, freshRules).progress.isEmpty())

        val grown = BaselineRatchet.compare(baseline, moved.copy(edges = listOf(edge(4))), rules)
        assertTrue(grown.violations.isEmpty())
    }

    @Test
    fun `matching uses exact or dotted prefixes of names and packages`() {
        val current = graph(listOf(edge(1)))
        val baseline = BaselineRatchet.baseline(graph(emptyList()))
        listOf("legacy", "legacy.Old").forEach {
            val rules = listOf(DeniedDependency(to = it))
            assertEquals(1, BaselineRatchet.compare(baseline, current, rules).violations.size)
        }
        listOf("leg", "legacy.O", "legacy.Old.Member").forEach {
            val rules = listOf(DeniedDependency(to = it))
            assertTrue(BaselineRatchet.compare(baseline, current, rules).violations.isEmpty())
        }
        val nestedTarget = target.copy(fqName = "legacy.Old.Member", packageName = "legacy.internal")
        val nested = current.copy(nodes = listOf(source, nestedTarget))
        val nameRule = listOf(DeniedDependency(to = "legacy.Old"))
        val packageRule = listOf(DeniedDependency(to = "legacy.internal"))
        assertEquals(1, BaselineRatchet.compare(baseline, nested, nameRule).violations.size)
        assertEquals(1, BaselineRatchet.compare(baseline, nested, packageRule).violations.size)
        val userIndex = current.copy(nodes = listOf(source, target.copy(fqName = "legacy.UserIndex")))
        val userRule = listOf(DeniedDependency(to = "legacy.User"))
        assertTrue(BaselineRatchet.compare(baseline, userIndex, userRule).violations.isEmpty())
    }

    @Test
    fun `pair rules require matching source and target prefixes`() {
        val baseline = BaselineRatchet.baseline(graph(emptyList()))
        val current = graph(listOf(edge(1)))
        listOf(null, "a", "a.Main").forEach { from ->
            val rules = listOf(DeniedDependency(from = from, to = "legacy"))
            assertEquals(1, BaselineRatchet.compare(baseline, current, rules).violations.size)
        }
        listOf("other", "a.Mai", "a.Main.Member").forEach { from ->
            val rules = listOf(DeniedDependency(from = from, to = "legacy"))
            assertTrue(BaselineRatchet.compare(baseline, current, rules).violations.isEmpty())
        }
        val nestedSource = source.copy(fqName = "a.domain.Main", packageName = "a.internal")
        val nested = current.copy(nodes = listOf(nestedSource, target))
        listOf("a", "a.domain", "a.internal").forEach { from ->
            val rules = listOf(DeniedDependency(from = from, to = "legacy"))
            assertEquals(1, BaselineRatchet.compare(baseline, nested, rules).violations.size)
        }
        val mismatch = listOf(DeniedDependency(from = "a", to = "other"))
        assertTrue(BaselineRatchet.compare(baseline, current, mismatch).violations.isEmpty())
    }

    private fun graph(edges: List<GraphEdge>) =
        DependencyGraph(generatedAt = "now", projectRoot = "/checkout", nodes = listOf(source, target), edges = edges)

    private fun edge(count: Int) =
        GraphEdge(
            source.id,
            target.id,
            EdgeKind.FUNCTION_CALL,
            List(count) { CallSite("Main.kt", it + 1, 1) },
        )

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
