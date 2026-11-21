package io.github.ninebolt6.klasstree.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlassTreeRatchetFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    private val source =
        """
        package a
        import legacy.Old
        class Main {
            fun use(value: Old) {
                value.touch()
            }
        }
        """.trimIndent()

    @Test
    fun `line shifts pass increased counts fail and removals report progress`() {
        writeProject()
        runner("klassTreeBaseline", "--configuration-cache").build()
        val baselineFile = projectDir.resolve("klass-tree-baseline.json").toFile()
        val baseline = baselineFile.readText()

        val shifted = source.replace("        value.touch()", "        // a comment\n        value.touch()")
        write("src/main/kotlin/a/Main.kt", shifted)
        val unchanged = runner("klassTreeCheck", "--configuration-cache").build()
        assertTrue(unchanged.output.contains("0 edge(s) with call-site count changes"))
        assertTrue(readCountDiff().getValue("changes").jsonArray.isEmpty())
        runner("klassTreeBaseline").build()
        assertEquals(baseline, baselineFile.readText())

        val increasedCalls = shifted.replace("        value.touch()", "        value.touch()\n        value.touch()")
        write("src/main/kotlin/a/Main.kt", increasedCalls)
        val increased = runner("klassTreeCheck", "--configuration-cache").buildAndFail()
        assertTrue(increased.output.contains("Configuration cache entry reused"))
        assertTrue(increased.output.contains("a.Main -> legacy.Old FUNCTION_CALL (1 -> 2 call sites) @"))
        assertTrue(increased.output.contains("Main.kt:6"))
        assertTrue(increased.output.contains("Main.kt:7"))
        val violation =
            readCountDiff()
                .getValue("violations")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("1", violation.getValue("baseline").jsonPrimitive.content)
        assertEquals("2", violation.getValue("current").jsonPrimitive.content)

        write("src/main/kotlin/a/Main.kt", shifted.replace("        value.touch()\n", ""))
        val removed = runner("klassTreeCheck", "--configuration-cache").build()
        assertTrue(removed.output.contains("1 denied reference(s) removed since the baseline"))
        val progress =
            readCountDiff()
                .getValue("progress")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("0", progress.getValue("current").jsonPrimitive.content)
    }

    @Test
    fun `old graph baselines fail with regeneration instructions`() {
        writeProject()
        runner("klassTree").build()
        Files.copy(projectDir.resolve("build/klass-tree/graph.json"), projectDir.resolve("klass-tree-baseline.json"))

        val result = runner("klassTreeCheck").buildAndFail()

        assertTrue(result.output.contains("cannot parse baseline"))
        assertTrue(result.output.contains("klassTreeBaseline` to regenerate"))
    }

    private fun readCountDiff(): JsonObject {
        val file = projectDir.resolve("build/klass-tree/klass-tree-diff.json").toFile()
        return Json.parseToJsonElement(file.readText()).jsonObject
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
            rootProject.name = "ratchet-functional"
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("io.github.ninebolt6.klass-tree")
            }
            repositories { mavenCentral() }
            klassTree { deniedTargets.set(listOf("legacy")) }
            """,
        )
        write("src/main/kotlin/legacy/Old.kt", "package legacy\nclass Old { fun touch() = Unit }")
        write("src/main/kotlin/a/Main.kt", source)
    }

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
