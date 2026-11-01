package io.github.klasstree.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlassTreeFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `help does not resolve source set dependencies`() {
        writeProject(invalidDependency = true)

        val result = runner("help").build()

        assertNotNull(result.task(":help"))
    }

    @Test
    fun `klassTree analyzes project dependencies without compiling their outputs`() {
        writeProject()

        val result = runner("klassTree").build()
        val graph = projectDir.resolve("build/klass-tree/graph.json").toFile().readText()

        assertTrue(graph.contains("\"from\": \":api:main:"))
        assertTrue(graph.contains("\"to\": \":shared:main:"))
        assertNull(result.task(":shared:compileKotlin"))
        assertNull(result.task(":shared:jar"))
    }

    @Test
    fun `klassTree reuses the configuration cache and task outputs`() {
        writeProject()

        runner("klassTree", "--configuration-cache").build()
        val second = runner("klassTree", "--configuration-cache").build()

        assertTrue(second.output.contains("Configuration cache entry reused"))
        assertTrue(second.output.contains(":klassTree UP-TO-DATE"))
        assertTrue(second.task(":klassTree")?.outcome == TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `test source set excludes project outputs and depends on main module`() {
        writeProject(includeTests = true)

        runner("klassTree").build()

        val spec = Json.parseToJsonElement(
            projectDir.resolve("build/klass-tree/analysis-spec.json").toFile().readText(),
        ).jsonObject
        val testModule = spec.getValue("modules").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("projectPath").jsonPrimitive.content == ":api" &&
                it.getValue("sourceSetName").jsonPrimitive.content == "test" }
        val classpath = testModule.getValue("classpath").jsonArray
            .map { it.jsonPrimitive.content }
        val dependencies = testModule.getValue("dependencies").jsonArray
            .map { it.jsonPrimitive.content }

        assertFalse(classpath.any { it.contains("/build/classes/") })
        assertTrue(":api:main" in dependencies)
    }

    @Test
    fun `klassTree reruns when a source file changes`() {
        writeProject()

        runner("klassTree").build()
        write(
            "api/src/main/kotlin/api/Api.kt",
            """
                package api

                import shared.Shared

                class Api(val shared: Shared)

                class Extra
            """,
        )
        val second = runner("klassTree").build()

        assertTrue(second.task(":klassTree")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(projectDir.resolve("build/klass-tree/graph.json").toFile().readText()
            .contains("\"fqName\": \"api.Extra\""))
    }

    @Test
    fun `klassTree analyzes the root project itself`() {
        writeProject(rootIsKotlin = true)

        val result = runner("klassTree").build()
        val graph = Json.parseToJsonElement(
            projectDir.resolve("build/klass-tree/graph.json").toFile().readText(),
        ).jsonObject
        val nodes = graph.getValue("nodes").jsonArray.map { it.jsonObject }
        val edges = graph.getValue("edges").jsonArray.map { it.jsonObject }
        val rootNodes = nodes.filter { it.getValue("module").jsonPrimitive.content == "root" }

        assertNotNull(result.task(":klassTree"))
        assertTrue(rootNodes.isNotEmpty())
        assertTrue(
            edges.any { edge ->
                edge.getValue("from").jsonPrimitive.content in rootNodes.map { it.getValue("id").jsonPrimitive.content } &&
                    edge.getValue("to").jsonPrimitive.content.startsWith(":shared:main:")
            },
        )
    }

    @Test
    fun `klassTree fails when there are no Kotlin sources`() {
        writeProject(withSources = false)

        val result = runner("klassTree").buildAndFail()

        assertTrue(result.output.contains("found no Kotlin source roots"))
    }

    @Test
    fun `forked analyzer classpath excludes Gradle and Kotlin compiler jars`() {
        writeProject()

        val result = runner("klassTree", "--info").build()
        val command = result.output.lineSequence()
            .first { it.contains("AnalyzerMainKt") && it.contains(" -cp ") }
        val classpath = command.substringAfter(" -cp ")
            .substringBefore(" io.github.klasstree.analyzer.AnalyzerMainKt")
            .split(java.io.File.pathSeparator)

        assertTrue(classpath.isNotEmpty())
        assertTrue(classpath.none { java.io.File(it).name.startsWith("gradle-") })
        assertTrue(classpath.none { java.io.File(it).name.startsWith("kotlin-compiler-embeddable") })
        assertTrue(classpath.any { it.contains("analyzer") })
    }

    private fun writeProject(
        includeTests: Boolean = false,
        invalidDependency: Boolean = false,
        rootIsKotlin: Boolean = false,
        withSources: Boolean = true,
    ) {
        write(
            "settings.gradle.kts",
            """
                pluginManagement {
                    repositories {
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "klass-tree-functional"
                include("shared", "api")
            """,
        )
        write(
            "build.gradle.kts",
            buildString {
                appendLine(
                    """
                        plugins {
                            id("org.jetbrains.kotlin.jvm")${if (rootIsKotlin) "" else " apply false"}
                            id("io.github.klass-tree")
                        }

                        klassTree {
                            includeTests.set($includeTests)
                            outputDirectory.set(layout.buildDirectory.dir("klass-tree"))
                        }
                    """.trimIndent(),
                )
                if (rootIsKotlin) {
                    appendLine(
                        """
                            dependencies {
                                implementation(project(":shared"))
                            }
                        """.trimIndent(),
                    )
                }
            },
        )
        write(
            "shared/build.gradle.kts",
            """
                plugins {
                    id("org.jetbrains.kotlin.jvm")
                }
            """,
        )
        write(
            "api/build.gradle.kts",
            buildString {
                appendLine(
                    """
                        plugins {
                            id("org.jetbrains.kotlin.jvm")
                        }

                        dependencies {
                            implementation(project(":shared"))
                    """.trimIndent(),
                )
                if (invalidDependency) {
                    appendLine("""    implementation("does.not:exist:1.0")""")
                }
                appendLine("}")
            },
        )
        if (!withSources) return
        write(
            "shared/src/main/kotlin/shared/Shared.kt",
            """
                package shared

                class Shared
            """,
        )
        write(
            "api/src/main/kotlin/api/Api.kt",
            """
                package api

                import shared.Shared

                class Api(val shared: Shared)
            """,
        )
        write(
            "shared/src/test/kotlin/shared/SharedTest.kt",
            """
                package shared

                class SharedTest
            """,
        )
        write(
            "api/src/test/kotlin/api/ApiTest.kt",
            """
                package api

                class ApiTest
            """,
        )
        if (rootIsKotlin) {
            write(
                "src/main/kotlin/root/Root.kt",
                """
                    package root

                    class Root(val s: shared.Shared)
                """,
            )
        }
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace", "--console=plain")
}
