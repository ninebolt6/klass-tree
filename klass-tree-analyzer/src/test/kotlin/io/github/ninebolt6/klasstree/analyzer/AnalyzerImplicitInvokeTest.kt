package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalyzerImplicitInvokeTest {
    @Test
    fun `calling a function-typed property records a property access edge`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Model.kt" to """
                package com.example

                class Holder(val cb: () -> Unit, val f: Holder.() -> Int)

                object Bus {
                    val cb: () -> Unit = {}
                }
                """,
                    "Uses.kt" to """
                package com.example

                val cb: () -> Unit = {}

                class Uses {
                    fun use(h: Holder, nh: Holder?) {
                        h.cb()
                        Bus.cb()
                        cb()
                        h.f()
                        nh?.cb()
                    }
                }
                """,
                ),
            )

        val holderEdge =
            assertNotNull(
                edge(result.graph, "com.example.Uses", "com.example.Holder", EdgeKind.PROPERTY_ACCESS),
                "h.cb() and h.f() must access the property",
            )
        assertEquals(3, holderEdge.callSites.size, "one call site per invoked property, including the safe call")
        assertNotNull(
            edge(result.graph, "com.example.Uses", "com.example.Bus", EdgeKind.PROPERTY_ACCESS),
            "Bus.cb() must access the property",
        )
        val fileScope =
            result.graph.nodes.single { it.kind == NodeKind.FILE_SCOPE && it.name == "Uses.kt" }
        val uses = result.graph.nodes.single { it.fqName == "com.example.Uses" }
        assertNotNull(
            result.graph.edges.singleOrNull {
                it.from == uses.id && it.to == fileScope.id && it.kind == EdgeKind.PROPERTY_ACCESS
            },
            "cb() on a top-level function-typed property must access the property",
        )
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }

    @Test
    fun `explicit invoke calls still record a single property access edge`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Model.kt" to """
                package com.example

                class Holder(val cb: () -> Unit)
                """,
                    "Uses.kt" to """
                package com.example

                class Uses {
                    fun use(h: Holder) {
                        h.cb?.invoke()
                        (h.cb)()
                    }
                }
                """,
                ),
            )

        val propertyEdge =
            assertNotNull(
                edge(result.graph, "com.example.Uses", "com.example.Holder", EdgeKind.PROPERTY_ACCESS),
            )
        assertEquals(2, propertyEdge.callSites.size, "one call site per explicit form, no duplicates")
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }

    @Test
    fun `calling a function-typed Java field records a property access edge`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "JHolder.java" to """
                package com.example;

                public class JHolder {
                    public kotlin.jvm.functions.Function0<kotlin.Unit> cb = () -> {};
                }
                """,
                    "Uses.kt" to """
                package com.example

                class Uses {
                    fun use(jh: JHolder) {
                        jh.cb()
                    }
                }
                """,
                ),
            )

        assertNotNull(
            edge(result.graph, "com.example.Uses", "com.example.JHolder", EdgeKind.PROPERTY_ACCESS),
            "jh.cb() must access the Java field",
        )
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }
}
