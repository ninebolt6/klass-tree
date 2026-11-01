package io.github.klasstree.gradle

import kotlinx.serialization.Serializable
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.io.File

@Serializable
internal data class AnalysisModuleCandidate(
    val projectPath: String,
    val sourceSetName: String,
    val moduleId: String,
    val moduleName: String,
    val sourceRoots: List<String>,
    val classpath: List<String>,
    val projectDependencyPaths: List<String>,
    val associatedModuleIds: List<String>,
)

internal data class KotlinProjectScan(
    val sourceDirectories: List<File>,
    val moduleTasks: List<TaskProvider<KlassTreeModuleSpecTask>>,
)

/**
 * Registers module-spec tasks from the Kotlin JVM Gradle model.
 *
 * This class is deliberately separate from [KlassTreePlugin]: it references Kotlin Gradle plugin
 * API types and is only loaded when the consuming build actually applies the Kotlin JVM plugin.
 * If the Kotlin plugin is not on the root buildscript classpath, the plugin reports a clear error
 * instead of failing with a class-loading exception in the Gradle daemon.
 */
@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal object KotlinProjectScanner {
    fun configureProject(project: Project, includeTests: Boolean): KotlinProjectScan {
        if (!project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            return KotlinProjectScan(emptyList(), emptyList())
        }

        val moduleElements = project.configurations.consumable("klassTreeModuleElements") { configuration ->
            configuration.attributes.attribute(
                Usage.USAGE_ATTRIBUTE,
                project.objects.named(Usage::class.java, MODULE_SPEC_USAGE),
            )
        }
        val kotlin = project.extensions.findByType(KotlinJvmExtension::class.java)
            ?: return KotlinProjectScan(emptyList(), emptyList())
        val sourceDirectories = LinkedHashSet<File>()
        val moduleTasks = mutableListOf<TaskProvider<KlassTreeModuleSpecTask>>()
        val seenSourceSets = mutableSetOf<String>()

        kotlin.target.compilations.forEach { compilation ->
            if (compilation.platformType != KotlinPlatformType.jvm) return@forEach
            val sourceSet = compilation.defaultSourceSet
            if (!includeTests && sourceSet.name != "main") return@forEach
            if (!seenSourceSets.add(sourceSet.name)) return@forEach

            val directories = sourceDirectories(sourceSet)
            sourceDirectories += directories
            val dependencyConfiguration = project.configurations.named(
                compilation.compileDependencyConfigurationName,
            )
            val projectDependencyPaths = dependencyConfiguration.get().allDependencies
                .filterIsInstance<ProjectDependency>()
                .mapTo(LinkedHashSet()) { it.path }
            val associatedModuleIds = compilation.allAssociatedCompilations
                .mapTo(LinkedHashSet()) { moduleId(project.path, it.defaultSourceSet.name) }
            val taskName = "klassTreeModuleSpec${sourceSet.name.replaceFirstChar { it.uppercase() }}"
            val moduleTask = project.tasks.register(taskName, KlassTreeModuleSpecTask::class.java) { task ->
                task.projectPath.set(project.path)
                task.sourceSetName.set(sourceSet.name)
                task.moduleId.set(moduleId(project.path, sourceSet.name))
                task.moduleName.set(moduleName(project.path, sourceSet.name))
                task.projectDependencyPaths.set(projectDependencyPaths)
                task.associatedModuleIds.set(associatedModuleIds)
                task.sourceDirectories.from(directories)
                task.classpath.from(
                    dependencyConfiguration.map { configuration ->
                        configuration.incoming.artifactView { view ->
                            view.componentFilter { id -> id !is ProjectComponentIdentifier }
                        }.files
                    },
                )
                task.outputFile.set(
                    project.layout.buildDirectory.file("klass-tree/modules/${sourceSet.name}.json"),
                )
            }
            moduleElements.configure { configuration ->
                configuration.outgoing.artifact(moduleTask.flatMap { it.outputFile })
            }
            moduleTasks += moduleTask
        }
        return KotlinProjectScan(sourceDirectories.toList(), moduleTasks)
    }

    private fun sourceDirectories(sourceSet: org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet): List<File> {
        val directories = LinkedHashSet<File>()
        directories += sourceSet.kotlin.srcDirs
        directories += sourceSet.generatedKotlin.srcDirs
        return directories.toList()
    }

    private fun moduleId(projectPath: String, sourceSetName: String): String =
        (if (projectPath == ":") ":" else "$projectPath:") + sourceSetName

    private fun moduleName(projectPath: String, sourceSetName: String): String {
        val projectPart = projectPath.removePrefix(":").ifEmpty { "root" }
        return sanitize(projectPart) + "_" + sanitize(sourceSetName)
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "root" }

    private const val MODULE_SPEC_USAGE = "klass-tree-module-spec"
}
