package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphEdge
import io.github.ninebolt6.klasstree.graph.ModuleSpec
import io.github.ninebolt6.klasstree.graph.NodeKind
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerInteropTest {
    @Test
    fun `java call to a JvmName facade reaches the file scope node`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "K.kt" to """
                    @file:JvmName("Helpers")
                    package com.example

                    fun topLevel() {}
                """,
                    "Caller.java" to """
                    package com.example;
                    public class Caller {
                        void call() { Helpers.topLevel(); }
                    }
                """,
                ),
            )
        val fileScope = result.graph.nodes.single { it.kind == NodeKind.FILE_SCOPE }
        val caller = result.graph.nodes.single { it.fqName == "com.example.Caller" }
        assertTrue(
            result.graph.edges.any {
                it.from == caller.id && it.to == fileScope.id && it.kind == EdgeKind.FUNCTION_CALL
            },
            "@JvmName facade calls must resolve to the FILE_SCOPE node, got edges " + result.graph.edges,
        )
    }

    @Test
    fun `kotlin sees java members as calls and property accesses`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Bean.java" to """
                    package com.example;
                    public class Bean {
                        public static int total = 0;
                        public String name = "";
                        public static Bean create() { return new Bean(); }
                    }
                """,
                    "Use.kt" to """
                    package com.example

                    fun use(b: Bean): String {
                        Bean.total = Bean().name.length
                        Bean.create()
                        return b.name
                    }
                """,
                ),
            )
        assertNotNull(
            edge(result.graph, "com.example", "com.example.Bean", EdgeKind.CONSTRUCTOR_CALL),
            "Kotlin sees the Java constructor",
        )
        assertNotNull(
            edge(result.graph, "com.example", "com.example.Bean", EdgeKind.PROPERTY_ACCESS),
            "Kotlin sees Java public fields as property accesses",
        )
    }

    @Test
    fun `kotlin inherits java interface and java reaches kotlin object`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Spec.java" to "package com.example; public interface Spec { boolean ok(); }",
                    "Impl.kt" to """
                    package com.example

                    class Impl : Spec {
                        override fun ok() = true
                    }
                """,
                    "Ids.kt" to """
                    package com.example

                    object Ids { const val prefix = "x" }
                """,
                    "UseIds.java" to """
                    package com.example;
                    public class UseIds { String p = Ids.prefix; }
                """,
                ),
            )
        assertNotNull(
            edge(result.graph, "com.example.Impl", "com.example.Spec", EdgeKind.INHERITANCE),
            "Kotlin class implementing a Java interface",
        )
        assertNotNull(
            edge(result.graph, "com.example.UseIds", "com.example.Ids", EdgeKind.PROPERTY_ACCESS),
            "Java reading a Kotlin object const",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java record and kotlin companion access`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Pair.java" to "package com.example; public record Pair(int a, int b) {}",
                    "K.kt" to """
                    package com.example

                    class K {
                        companion object {
                            @JvmStatic
                            fun make() = K()
                        }
                    }
                """,
                    "Use.java" to """
                    package com.example;
                    public class Use {
                        Pair p = new Pair(1, 2);
                        int total = p.a();
                        K k = K.make();
                    }
                """,
                ),
            )
        assertNotNull(edge(result.graph, "com.example.Use", "com.example.Pair", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.Pair", EdgeKind.FUNCTION_CALL),
            "record accessor",
        )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.K", EdgeKind.FUNCTION_CALL),
            "@JvmStatic companion call",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java reaches kotlin companion through Companion and annotations cross both ways`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "K.kt" to """
                    package com.example

                    annotation class KAnn

                    class K {
                        companion object { fun make() = K() }
                    }
                """,
                    "JAnn.java" to "package com.example; public @interface JAnn {}",
                    "Use.java" to """
                    package com.example;
                    @KAnn
                    public class Use { K k = K.Companion.make(); }
                """,
                    "Use2.kt" to """
                    package com.example

                    @JAnn
                    class Use2
                """,
                ),
            )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.K.Companion", EdgeKind.FUNCTION_CALL),
            "companion call lands on the companion node, as in Kotlin",
        )
        assertNotNull(
            edge(result.graph, "com.example.Use", "com.example.KAnn", EdgeKind.ANNOTATION),
            "Java class annotated with a Kotlin annotation",
        )
        assertNotNull(
            edge(result.graph, "com.example.Use2", "com.example.JAnn", EdgeKind.ANNOTATION),
            "Kotlin class annotated with a Java annotation",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `includeJavaSources false still analyzes kotlin fully`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "A.java" to "package com.example; public class A {}",
                    "K.kt" to "package com.example\nclass K(val s: String)",
                ),
                includeJavaSources = false,
            )
        assertNotNull(result.graph.nodes.singleOrNull { it.fqName == "com.example.K" })
        assertTrue(result.graph.nodes.none { it.fqName == "com.example.A" })
    }
}
