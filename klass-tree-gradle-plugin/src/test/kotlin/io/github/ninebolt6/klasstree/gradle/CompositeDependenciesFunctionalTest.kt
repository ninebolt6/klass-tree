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

class CompositeDependenciesFunctionalTest {
    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `included build root is not mistaken for the local root project`() {
        writeProject()
        projectDir.resolve("settings.gradle.kts").toFile().appendText("\nincludeBuild(\"included\")\n")
        write("included/settings.gradle.kts", "rootProject.name = \"foreign\"")
        write(
            "included/build.gradle.kts",
            """
            plugins { `java-library` }
            group = "external"
            version = "1.0"
            """,
        )
        write(
            "shared/build.gradle.kts",
            """
            plugins { id("org.jetbrains.kotlin.jvm") }
            dependencies { implementation("external:foreign:1.0") }
            """,
        )

        runner("klassTree").build()

        assertNoSharedProjectDependencies()
    }

    @Test
    fun `module candidates exclude the resolved root component`() {
        writeProject()

        runner(":shared:klassTreeModuleSpecMain").build()

        assertNoSharedProjectDependencies()
    }

    private fun assertNoSharedProjectDependencies() {
        val candidates =
            Json
                .parseToJsonElement(
                    projectDir.resolve("shared/build/klass-tree/modules/main.json").toFile().readText(),
                ).jsonArray
        assertEquals(
            emptyList(),
            candidates
                .single()
                .jsonObject
                .getValue("projectDependencyPaths")
                .jsonArray
                .toList(),
        )
    }

    private fun writeProject() {
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "composite-dependencies"
            include("shared")
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("io.github.ninebolt6.klass-tree")
            }
            dependencies { implementation(project(":shared")) }
            """,
        )
        write("shared/build.gradle.kts", "plugins { id(\"org.jetbrains.kotlin.jvm\") }")
        write("src/main/kotlin/Root.kt", "class Root")
        write("shared/src/main/kotlin/Shared.kt", "class Shared")
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
