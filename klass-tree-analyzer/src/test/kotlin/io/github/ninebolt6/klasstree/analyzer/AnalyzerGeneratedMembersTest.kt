package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalyzerGeneratedMembersTest {
    @Test
    fun `enum generated members record call and access edges to the enum`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Uses.kt" to """
                package com.example

                enum class E { A, B }

                class Uses {
                    fun valueOf() = E.valueOf("A")
                    fun values() = E.values()
                    fun entries() = E.entries
                    fun entry() = E.A
                }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)

        // Each member resolves to a separate call site on the same merged edge.
        val functionCallEdge =
            assertNotNull(edge(result.graph, "com.example.Uses", "com.example.E", EdgeKind.FUNCTION_CALL))
        assertEquals(setOf(6, 7), functionCallEdge.callSites.map { it.line }.toSet())
        val propertyAccessEdge =
            assertNotNull(edge(result.graph, "com.example.Uses", "com.example.E", EdgeKind.PROPERTY_ACCESS))
        assertEquals(setOf(8, 9), propertyAccessEdge.callSites.map { it.line }.toSet())
    }

    @Test
    fun `data class generated members still do not produce edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Uses.kt" to """
                package com.example

                data class D(val x: Int)

                class Uses {
                    fun copy(d: D) = d.copy(x = 2)
                    fun destructure(d: D) { val (x) = d }
                }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertEquals(null, edge(result.graph, "com.example.Uses", "com.example.D", EdgeKind.FUNCTION_CALL))
        assertEquals(null, edge(result.graph, "com.example.Uses", "com.example.D", EdgeKind.PROPERTY_ACCESS))
    }
}
