package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalyzerConstructorCallTest {
    @Test
    fun `delegating and supertype constructor calls produce constructor call edges`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Model.kt" to """
                package com.example

                open class Base(val n: Int)

                class Child : Base {
                    constructor() : super(1)
                    constructor(x: String) : this()
                }

                class Other : Base(42)
                """,
                ),
            )

        assertNotNull(edge(result.graph, "com.example.Child", "com.example.Base", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(edge(result.graph, "com.example.Other", "com.example.Base", EdgeKind.CONSTRUCTOR_CALL))
        assertNotNull(edge(result.graph, "com.example.Other", "com.example.Base", EdgeKind.INHERITANCE))
        assertEquals(0, result.stats.resolution.unresolved)
        assertEquals(emptyList(), result.stats.resolution.analysisErrors)
    }
}
