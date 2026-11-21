package io.github.ninebolt6.klasstree.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        runner("klassTreeBaseline").build()

        val content = projectDir.resolve("klass-tree-baseline.json").toFile().readText()
        val baseline = Json.parseToJsonElement(content).jsonObject
        assertEquals(setOf("schemaVersion", "edges"), baseline.keys)
        assertTrue(baseline.getValue("edges").jsonArray.isNotEmpty())

        val shifted = source.replace("        value.touch()", "        // a comment\n        value.touch()")
        write("src/main/kotlin/a/Main.kt", shifted)
        runner("klassTreeCheck").build()

        val increasedCalls = shifted.replace("        value.touch()", "        value.touch()\n        value.touch()")
        write("src/main/kotlin/a/Main.kt", increasedCalls)
        val increased = runner("klassTreeCheck").buildAndFail()
        assertTrue(increased.output.contains("a.Main -> legacy.Old FUNCTION_CALL (1 -> 2 call sites)"))

        write("src/main/kotlin/a/Main.kt", shifted.replace("        value.touch()\n", ""))
        val removed = runner("klassTreeCheck").build()
        assertTrue(removed.output.contains("1 denied reference(s) removed since the baseline"))
    }

    @Test
    fun `deny pairs ignore other sources and reject matching sources with configuration cache`() {
        writeProject("""deny(from = "a", to = "legacy")""")
        runner("klassTreeBaseline").build()

        write("src/main/kotlin/b/Main.kt", source.replace("package a", "package b"))
        runner("klassTreeCheck", "--configuration-cache").build()
        assertTrue(projectDir.resolve("build/klass-tree/klass-tree-diff.json").toFile().isFile)

        write("src/main/kotlin/a/Main.kt", source.replace("value.touch()", "value.touch()\n        value.touch()"))
        val result = runner("klassTreeCheck", "--configuration-cache").buildAndFail()

        assertTrue(result.output.contains("Configuration cache entry reused"))
        assertTrue(result.output.contains("new reference(s) violating [a -> legacy]"))
        assertTrue(result.output.contains("a.Main -> legacy.Old FUNCTION_CALL (1 -> 2 call sites)"))
    }

    private fun writeProject(rule: String = """deny(to = "legacy")""") {
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
            klassTree { $rule }
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
