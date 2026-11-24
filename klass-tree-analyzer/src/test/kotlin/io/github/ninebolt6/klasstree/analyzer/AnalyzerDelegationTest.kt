package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalyzerDelegationTest {
    @Test
    fun `delegated function calls land on the declaring interface`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Delegation.kt" to """
                package com.example
                interface I { fun f() }
                class A(i: I) : I by i
                class Use { fun g(a: A) = a.f() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.I", EdgeKind.FUNCTION_CALL))
        assertEquals(null, edge(result.graph, "com.example.Use", "com.example.A", EdgeKind.FUNCTION_CALL))
    }

    @Test
    fun `delegated property accesses land on the declaring interface`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Delegation.kt" to """
                package com.example
                interface I { val x: Int }
                class A(i: I) : I by i
                class Use { fun g(a: A) = a.x }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.I", EdgeKind.PROPERTY_ACCESS))
    }

    @Test
    fun `delegated callable references land on the declaring interface`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Delegation.kt" to """
                package com.example
                interface I { fun f() }
                class A(i: I) : I by i
                class Use { val r = A(object : I { override fun f() {} })::f }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.I", EdgeKind.CALLABLE_REFERENCE))
    }

    @Test
    fun `delegated member calls inside the delegating class land on the interface`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Delegation.kt" to """
                package com.example
                interface I { fun f() }
                class A(i: I) : I by i { fun h() = f() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.A", "com.example.I", EdgeKind.FUNCTION_CALL))
    }

    @Test
    fun `generic delegated member calls land on the declaring interface`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "GenericDelegation.kt" to """
                package com.example
                interface I<T> { fun f(): T }
                class A(i: I<String>) : I<String> by i
                class Use { fun g(a: A) = a.f() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.I", EdgeKind.FUNCTION_CALL))
    }
}
