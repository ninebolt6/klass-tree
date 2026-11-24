package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnalyzerSealedTest {
    @Test
    fun `java sealed permits records inheritance from subclass to sealed parent`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Shape.java" to """
                    package com.example;
                    public sealed class Shape permits Circle, Square {}
                """,
                    "Circle.java" to """
                    package com.example;
                    public final class Circle extends Shape {}
                """,
                    "Square.java" to """
                    package com.example;
                    public final class Square extends Shape {}
                """,
                ),
            )

        val circle = edge(result.graph, "com.example.Circle", "com.example.Shape", EdgeKind.INHERITANCE)
        assertNotNull(circle, "permits must record the subclass-to-parent INHERITANCE edge")
        assertEquals(
            2,
            circle.callSites.size,
            "'extends Shape' and 'permits Circle' both declare Circle extends Shape, got " + circle.callSites,
        )
        assertNotNull(edge(result.graph, "com.example.Square", "com.example.Shape", EdgeKind.INHERITANCE))
        assertNull(
            edge(result.graph, "com.example.Shape", "com.example.Circle", EdgeKind.INHERITANCE),
            "INHERITANCE edges point subclass to superclass",
        )
        assertNull(
            edge(result.graph, "com.example.Shape", "com.example.Circle", EdgeKind.TYPE_REFERENCE),
            "a permits clause is an inheritance declaration, not a plain type mention",
        )
        assertEquals(0, result.stats.resolution.unresolved)
    }

    @Test
    fun `java sealed interface permits records inheritance for implementing subclass`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "I.java" to """
                    package com.example;
                    public sealed interface I permits A {}
                """,
                    "A.java" to """
                    package com.example;
                    public final class A implements I {}
                """,
                ),
            )

        assertNotNull(edge(result.graph, "com.example.A", "com.example.I", EdgeKind.INHERITANCE))
        assertNull(edge(result.graph, "com.example.I", "com.example.A", EdgeKind.INHERITANCE))
        assertNull(edge(result.graph, "com.example.I", "com.example.A", EdgeKind.TYPE_REFERENCE))
        assertEquals(0, result.stats.resolution.unresolved)
    }
}
