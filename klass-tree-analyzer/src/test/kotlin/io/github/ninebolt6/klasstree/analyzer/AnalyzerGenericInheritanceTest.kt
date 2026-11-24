package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalyzerGenericInheritanceTest {
    @Test
    fun `explicit generic overrides of multiple interfaces keep call edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Override.kt" to """
                package com.example
                interface A<T> { fun f(): T }
                interface B<T> { fun f(): T }
                open class Base<T>(val v: T) : A<T>, B<T> {
                    override fun f(): T = v
                }
                class Child : Base<String>("x")
                class Use { fun g(c: Child) = c.f() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.Base", EdgeKind.FUNCTION_CALL))
    }

    @Test
    fun `substituted intersections do not produce function call edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Intersection.kt" to """
                package com.example
                interface A<T> { fun f(): T }
                interface B<T> { fun f(): T }
                abstract class C<T> : A<T>, B<T>
                abstract class D : C<String>()
                class Use { fun g(d: D) = d.f() }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertEquals(emptyList(), result.graph.edges.filter { it.kind == EdgeKind.FUNCTION_CALL })
    }

    @Test
    fun `inherited generic Java fields produce property access edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "JBase.java" to "package com.example; public class JBase<T> { public T field; }",
                    "Use.kt" to """
                package com.example
                class KChild : JBase<String>()
                class Use { fun r(c: KChild) = c.field }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.JBase", EdgeKind.PROPERTY_ACCESS))
    }

    @Test
    fun `direct generic Java fields produce property access edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "JBase.java" to "package com.example; public class JBase<T> { public T field; }",
                    "Use.kt" to """
                package com.example
                class Use { fun r() = JBase<String>().field }
                """,
                ),
            )

        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.JBase", EdgeKind.PROPERTY_ACCESS))
    }

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
    fun `inherited generic delegated members land on the declaring interface`() {
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
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.I", EdgeKind.FUNCTION_CALL))
        assertEquals(null, edge(result.graph, "com.example.Use", "com.example.Impl", EdgeKind.FUNCTION_CALL))
    }
}
