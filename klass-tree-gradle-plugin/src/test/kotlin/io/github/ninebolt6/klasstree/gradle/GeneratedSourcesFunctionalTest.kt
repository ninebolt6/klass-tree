package io.github.ninebolt6.klasstree.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedSourcesFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `klassTree builds generated sources and reuses the configuration cache`() {
        writeProject()
        write(
            "shared/build.gradle.kts",
            """
                plugins {
                    id("org.jetbrains.kotlin.jvm")
                }

                abstract class GenerateKotlin : DefaultTask() {
                    @get:OutputDirectory
                    abstract val outputDir: DirectoryProperty

                    @TaskAction
                    fun generate() {
                        outputDir.file("shared/Generated.kt").get().asFile.apply {
                            parentFile.mkdirs()
                            writeText("package shared; class Generated")
                        }
                    }
                }

                val generateKotlin = tasks.register<GenerateKotlin>("generateKotlin") {
                    outputDir.set(layout.buildDirectory.dir("generated/kotlin"))
                }
                kotlin.sourceSets.main {
                    kotlin.srcDir(generateKotlin)
                }
            """,
        )
        write(
            "shared/src/main/kotlin/shared/Shared.kt",
            """
                package shared

                class Shared(val generated: Generated)
            """,
        )

        val result = runner("clean", "klassTree", "--configuration-cache").build()
        val graph = projectDir.resolve("build/klass-tree/graph.json").toFile().readText()

        assertEquals(TaskOutcome.SUCCESS, result.task(":shared:generateKotlin")?.outcome, result.output)
        assertTrue(graph.contains("\"fqName\": \"shared.Generated\""), graph)
        val json = Json.parseToJsonElement(graph).jsonObject
        val nodes = json.getValue("nodes").jsonArray.map { it.jsonObject }
        val generated = nodes.single { it.getValue("fqName").jsonPrimitive.content == "shared.Generated" }
        val shared = nodes.single { it.getValue("fqName").jsonPrimitive.content == "shared.Shared" }
        assertTrue(
            json.getValue("edges").jsonArray.any {
                it.jsonObject["from"] == shared["id"] && it.jsonObject["to"] == generated["id"]
            },
            graph,
        )
        assertNull(result.task(":shared:compileKotlin"))

        val second = runner("clean", "klassTree", "--configuration-cache").build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":shared:generateKotlin")?.outcome)
        assertTrue(graphFile().readText().contains("shared.Generated"))
    }

    private fun writeProject() {
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
                    repositories { mavenCentral() }
                }
                rootProject.name = "generated-sources-functional"
                include("shared")
            """,
        )
        write(
            "build.gradle.kts",
            """
                plugins {
                    id("org.jetbrains.kotlin.jvm") apply false
                    id("io.github.ninebolt6.klass-tree")
                }
            """,
        )
    }

    private fun graphFile() = projectDir.resolve("build/klass-tree/graph.json").toFile()

    private fun write(
        relativePath: String,
        content: String,
    ) {
        val file = projectDir.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content.trimIndent())
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace", "--console=plain")
}
