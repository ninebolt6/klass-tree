package io.github.ninebolt6.klasstree.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlassTreeClasspathFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `klassTree resolves compiler visible transitive project dependencies`() {
        writeProject()
        projectDir.resolve("settings.gradle.kts").toFile().appendText("\ninclude(\"bridge\", \"hidden\")\n")
        write(
            "api/build.gradle.kts",
            """
                plugins { kotlin("jvm") }
                dependencies { implementation(project(":bridge")) }
            """,
        )
        write(
            "bridge/build.gradle.kts",
            """
                plugins { kotlin("jvm"); `java-library` }
                dependencies {
                    api(project(":shared"))
                    implementation(project(":hidden"))
                }
            """,
        )
        write("bridge/src/main/kotlin/bridge/Bridge.kt", "package bridge; class Bridge")
        write("hidden/build.gradle.kts", "plugins { kotlin(\"jvm\") }")
        write("hidden/src/main/kotlin/hidden/Hidden.kt", "package hidden; class Hidden")

        val result = runner("klassTree").build()
        val graph = readOutput("graph.json")
        val nodes = graph.getValue("nodes").jsonArray.map { it.jsonObject }
        val apiId = nodes.single { it.getValue("fqName").jsonPrimitive.content == "api.Api" }.getValue("id")
        val sharedId = nodes.single { it.getValue("fqName").jsonPrimitive.content == "shared.Shared" }.getValue("id")
        val edges = graph.getValue("edges").jsonArray.map { it.jsonObject }
        val spec = readOutput("analysis-spec.json")
        val module =
            spec.getValue("modules").jsonArray.map { it.jsonObject }.single {
                it.getValue("id").jsonPrimitive.content == ":api:main"
            }

        assertTrue(
            edges.any {
                it.getValue("from") == apiId && it.getValue("to") == sharedId
            },
        )
        assertEquals(
            listOf(":bridge:main", ":shared:main"),
            module.getValue("dependencies").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(result.output.contains("unresolved=0"))
        assertNull(result.task(":shared:compileKotlin"))
        assertNull(result.task(":bridge:compileKotlin"))
        assertNull(result.task(":shared:jar"))
        assertNull(result.task(":bridge:jar"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `klassTree retains project binaries without analyzed sources`(kotlinPlugin: Boolean) {
        writeProject(klassTreeConfig = "includeJavaSources.set(false)")
        Files.delete(projectDir.resolve("shared/src/main/kotlin/shared/Shared.kt"))
        write(
            "shared/build.gradle.kts",
            """
                plugins { ${if (kotlinPlugin) "kotlin(\"jvm\")" else "`java-library`"} }
                tasks.compileJava { source("binary-src") }
                configurations.named("apiElements") {
                    outgoing.variants.removeIf { it.name == "classes" }
                }
            """,
        )
        write("shared/binary-src/shared/Shared.java", "package shared; public class Shared {}")

        val result = runner("klassTree", "--configuration-cache").build()
        val spec = readOutput("analysis-spec.json")
        val module =
            spec
                .getValue("modules")
                .jsonArray
                .single()
                .jsonObject
        val graph = projectDir.resolve("build/klass-tree/graph.json").toFile().readText()
        val classpath = module.getValue("classpath").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(":api:main", module.getValue("id").jsonPrimitive.content)
        assertTrue(classpath.any { it.endsWith("/shared/build/libs/shared.jar") })
        assertTrue(result.output.contains("unresolved=0"))
        assertTrue(Regex("external=([1-9][0-9]*)").containsMatchIn(result.output))
        assertFalse(graph.contains(":shared:main:"))
        assertNotNull(result.task(":shared:compileJava"))

        if (kotlinPlugin) {
            Files.delete(projectDir.resolve("shared/binary-src/shared/Shared.java"))
            write("shared/src/main/kotlin/shared/Shared.kt", "package shared; class Shared")

            val withSources = runner("klassTree", "--configuration-cache").build()
            val sourceGraph = projectDir.resolve("build/klass-tree/graph.json").toFile().readText()

            assertTrue(sourceGraph.contains(":shared:main:"))
            assertTrue(withSources.output.contains("unresolved=0"))
            assertNull(withSources.task(":shared:compileKotlin"))
            assertNull(withSources.task(":shared:compileJava"))
        }
    }

    private fun writeProject(klassTreeConfig: String = "") {
        write(
            "settings.gradle.kts",
            """
                pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
                dependencyResolutionManagement { repositories { mavenCentral() } }
                rootProject.name = "klass-tree-classpath-functional"
                include("api", "shared")
            """,
        )
        write(
            "build.gradle.kts",
            """
                plugins {
                    kotlin("jvm") apply false
                    id("io.github.ninebolt6.klass-tree")
                }
                klassTree { $klassTreeConfig }
            """,
        )
        write("shared/build.gradle.kts", "plugins { kotlin(\"jvm\") }")
        write("shared/src/main/kotlin/shared/Shared.kt", "package shared; class Shared")
        write(
            "api/build.gradle.kts",
            """
                plugins { kotlin("jvm") }
                dependencies { implementation(project(":shared")) }
            """,
        )
        write("api/src/main/kotlin/api/Api.kt", "package api; class Api(val shared: shared.Shared)")
    }

    private fun readOutput(name: String) =
        Json.parseToJsonElement(projectDir.resolve("build/klass-tree/$name").toFile().readText()).jsonObject

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
