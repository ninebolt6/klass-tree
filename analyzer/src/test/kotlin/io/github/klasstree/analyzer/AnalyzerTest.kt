package io.github.klasstree.analyzer

import io.github.klasstree.graph.AnalysisSpec
import io.github.klasstree.graph.EdgeKind
import io.github.klasstree.graph.ModuleSpec
import io.github.klasstree.graph.NodeKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerTest {
    @Test
    fun `builds source nodes and semantic edges without external nodes`() {
        val root = Files.createTempDirectory("klass-tree-analyzer-test")
        try {
            val sourceRoot = root.resolve("src/main/kotlin")
            Files.createDirectories(sourceRoot.resolve("com/example"))
            sourceRoot.resolve("com/example/Model.kt").toFile().writeText(
                """
                package com.example

                interface Repository {
                    fun find(id: String): User?
                }

                class User(val id: String)

                class UserService(private val repository: Repository) {
                    fun find(id: String): User? = repository.find(id)
                }

                class UserIndex {
                    private val byName: Map<String, List<User>> = emptyMap()
                    fun usersNamed(name: String): List<User> = byName[name].orEmpty()
                }

                fun defaultUser(): User = User("default")
                """.trimIndent(),
            )

            val stdlib = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .first { it.contains("kotlin-stdlib") }

            val spec = AnalysisSpec(
                projectRoot = root.toString(),
                modules = listOf(
                    ModuleSpec(
                        id = ":app:main",
                        projectPath = ":app",
                        sourceSetName = "main",
                        moduleName = "app_main",
                        sourceRoots = listOf(sourceRoot.toString()),
                        classpath = listOf(stdlib),
                    ),
                ),
            )

            val graph = Analyzer().analyze(spec, disposeGlobalServices = true)

            assertEquals(5, graph.nodes.size)
            assertEquals(
                setOf(
                    "com.example.Repository" to NodeKind.INTERFACE,
                    "com.example.User" to NodeKind.CLASS,
                    "com.example.UserService" to NodeKind.CLASS,
                    "com.example.UserIndex" to NodeKind.CLASS,
                ),
                graph.nodes.filter { it.kind != NodeKind.FILE_SCOPE }
                    .map { it.fqName to it.kind }
                    .toSet(),
            )
            assertNotNull(graph.nodes.singleOrNull { it.kind == NodeKind.FILE_SCOPE })
            assertTrue(graph.nodes.all { it.module == "app" && it.sourceSet == "main" })

            val service = graph.nodes.single { it.fqName == "com.example.UserService" }
            val repository = graph.nodes.single { it.fqName == "com.example.Repository" }
            val user = graph.nodes.single { it.fqName == "com.example.User" }
            val index = graph.nodes.single { it.fqName == "com.example.UserIndex" }

            assertTrue(
                graph.edges.any {
                    it.from == service.id && it.to == repository.id && it.kind == EdgeKind.TYPE_REFERENCE
                },
            )
            assertTrue(
                graph.edges.any {
                    it.from == service.id && it.to == repository.id && it.kind == EdgeKind.FUNCTION_CALL
                },
            )
            assertTrue(
                graph.edges.any {
                    it.from == service.id && it.to == user.id && it.kind == EdgeKind.TYPE_REFERENCE
                },
            )
            assertTrue(
                graph.edges.any {
                    it.kind == EdgeKind.CONSTRUCTOR_CALL && it.to == user.id
                },
            )
            assertTrue(
                graph.edges.any {
                    it.from == index.id && it.to == user.id && it.kind == EdgeKind.TYPE_REFERENCE
                },
            )

            assertEquals(0, graph.stats.resolution.analysisErrors.size)
            assertTrue(graph.stats.resolution.resolvedExternal > 0)
            assertTrue(graph.edges.none { it.from == it.to })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
