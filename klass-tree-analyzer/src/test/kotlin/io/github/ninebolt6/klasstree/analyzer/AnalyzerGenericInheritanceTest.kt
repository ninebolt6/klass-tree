package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalyzerGenericInheritanceTest {
    @Test
    fun `multilevel generic members point to their declaring class`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Multilevel.kt" to """
                package com.example

                open class Base<T>(val prop: T) {
                    fun value(): T = prop
                }
                open class Mid<T>(v: T) : Base<T>(v)
                class Leaf : Mid<String>("x")
                class Use { fun h(l: Leaf) = l.value() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        val call = assertNotNull(edge(result.graph, "com.example.Use", "com.example.Base", EdgeKind.FUNCTION_CALL))
        assertEquals(1, call.callSites.size)
        assertEquals(null, edge(result.graph, "com.example.Use", "com.example.Mid", EdgeKind.FUNCTION_CALL))
        assertEquals(null, edge(result.graph, "com.example.Use", "com.example.Leaf", EdgeKind.FUNCTION_CALL))
    }

    @Test
    fun `inherited generic delegated members do not produce call edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "GenericDelegation.kt" to """
                package com.example

                interface I<T> { fun f(): T }
                open class Impl<T>(d: I<T>) : I<T> by d
                class Child(d: I<String>) : Impl<String>(d)
                class Use { fun g(c: Child) = c.f() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertEquals(null, edge(result.graph, "com.example.Use", "com.example.I", EdgeKind.FUNCTION_CALL))
        assertEquals(null, edge(result.graph, "com.example.Use", "com.example.Impl", EdgeKind.FUNCTION_CALL))
    }
}
