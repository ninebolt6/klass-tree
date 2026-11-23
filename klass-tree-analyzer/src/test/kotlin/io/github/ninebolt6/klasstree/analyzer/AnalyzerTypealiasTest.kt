package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerTypealiasTest {
    @Test
    fun `typealias constructors resolve to the underlying source class`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Aliases.kt" to """
                package com.example

                class Foo
                typealias Alias = Foo
                class Caller {
                    fun direct() = Foo()
                    fun aliased() = Alias()
                }
                """,
                ),
            )

        val constructorEdge =
            assertNotNull(edge(result.graph, "com.example.Caller", "com.example.Foo", EdgeKind.CONSTRUCTOR_CALL))
        assertEquals(listOf(6, 7), constructorEdge.callSites.map { it.line }.sorted())
        assertEquals(1, result.graph.edges.count { it.kind == EdgeKind.CONSTRUCTOR_CALL })
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `typealias library constructors count as external like direct constructors`() {
        fun resolve(constructor: String) =
            analyze(
                module(
                    ":app:main",
                    "Aliases.kt" to """
                package com.example

                typealias Alias = java.lang.StringBuilder
                class Caller { fun create() = $constructor() }
                """,
                ),
            )

        val direct = resolve("java.lang.StringBuilder")
        val aliased = resolve("Alias")
        assertEquals(emptyList(), aliased.stats.resolution.analysisErrors)
        assertEquals(0, aliased.stats.resolution.unresolved)
        assertEquals(direct.stats.resolution.resolvedExternal, aliased.stats.resolution.resolvedExternal)
        assertTrue(aliased.graph.edges.none { it.kind == EdgeKind.CONSTRUCTOR_CALL })
        assertEquals(direct.graph.nodes, aliased.graph.nodes)
    }
}
