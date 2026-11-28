package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.EdgeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerResolutionStatsTest {
    @Test
    fun `unresolved references record kind and source sample`() {
        val result =
            analyze(
                module(
                    ":app:main",
                    "Broken.kt" to """
                package com.example

                class Broken {
                    val value: MissingType? = null
                }
                """,
                ),
            )

        assertTrue(result.stats.resolution.unresolved > 0)
        assertTrue((result.stats.resolution.unresolvedByKind[EdgeKind.TYPE_REFERENCE.name] ?: 0) > 0)
        val sample =
            result.stats.resolution.unresolvedSamples.single {
                it.kind == EdgeKind.TYPE_REFERENCE.name
            }
        assertEquals("src/app/main/Broken.kt", sample.file)
        assertEquals("MissingType", sample.text)
    }

    @Test
    fun `sample text truncation keeps surrogate pairs valid`() {
        assertEquals("a🚀", "a🚀".takeValidCodePoints(2))
        assertEquals("a", "a🚀".takeValidCodePoints(1))
        assertEquals("�", "\uD800".takeValidCodePoints(1))
        assertEquals("�", "\uDC00".takeValidCodePoints(1))
    }
}
