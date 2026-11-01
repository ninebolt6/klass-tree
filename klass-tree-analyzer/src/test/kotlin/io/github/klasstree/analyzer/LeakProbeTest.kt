package io.github.klasstree.analyzer

import io.github.klasstree.graph.AnalysisSpec
import io.github.klasstree.graph.ModuleSpec
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

class LeakProbeTest {
    @Test
    fun `repeated analyze calls release the session`() {
        val root = Files.createTempDirectory("klass-tree-leak-probe")
        try {
            val src = root.resolve("src")
            Files.createDirectories(src)
            repeat(60) { i ->
                src.resolve("Kt$i.kt").toFile().writeText(
                    """
                    package com.example

                    class Kt$i(private val other: J$i) {
                        fun go() = other.work()
                    }
                    """.trimIndent(),
                )
                src.resolve("J$i.java").toFile().writeText(
                    """
                    package com.example;
                    public class J$i {
                        public void work() {}
                        Kt$i k = new Kt$i(this);
                    }
                    """.trimIndent(),
                )
            }
            val spec = AnalysisSpec(
                projectRoot = root.toString(),
                includeJavaSources = true,
                modules = listOf(
                    ModuleSpec(
                        id = ":app:main",
                        projectPath = ":app",
                        sourceSetName = "main",
                        moduleName = "app_main",
                        sourceRoots = listOf(src.toString()),
                        classpath = listOf(stdlib()),
                        dependencies = emptyList(),
                    ),
                ),
            )

            fun usedHeap(): Long {
                repeat(3) {
                    System.gc()
                    Thread.sleep(20)
                }
                val runtime = Runtime.getRuntime()
                return runtime.totalMemory() - runtime.freeMemory()
            }

            usedHeap()
            val graph1 = Analyzer().analyze(spec)
            val afterFirst = usedHeap()
            repeat(2) {
                val graph = Analyzer().analyze(spec)
                assertTrue(graph.nodes.isNotEmpty())
            }
            val afterThird = usedHeap()

            println(
                "HEAP afterFirst=$afterFirst afterThird=$afterThird " +
                    "nodes=${graph1.nodes.size} edges=${graph1.edges.size}",
            )
            assertTrue(
                afterThird < afterFirst * 1.5 + 32L * 1024 * 1024,
                "session state appears retained across runs: " +
                    "afterFirst=$afterFirst afterThird=$afterThird",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun stdlib(): String =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .first { it.contains("kotlin-stdlib") }
}
