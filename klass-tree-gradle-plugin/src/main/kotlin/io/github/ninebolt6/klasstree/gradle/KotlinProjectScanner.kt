package io.github.ninebolt6.klasstree.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

internal data class KotlinProjectScan(
    val sourceDirectories: List<FileCollection>,
    val moduleTasks: List<TaskProvider<KlassTreeModuleSpecTask>>,
)

// Kotlin Gradle plugin API classes may be absent until the consuming build applies that plugin.
@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal object KotlinProjectScanner {
    private val minimumKgpVersion = KotlinVersion(2, 3)

    fun configureProject(
        project: Project,
        includeTests: Boolean,
        includeJavaSources: Boolean,
    ): KotlinProjectScan {
        val kotlin =
            if (project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
                requireSupportedVersion(project)
                project.extensions.findByType(KotlinJvmExtension::class.java)
            } else {
                null
            } ?: return KotlinProjectScan(emptyList(), emptyList())
        val buildPath = project.rootProject.buildTreePath
        val modules =
            kotlin.target.compilations
                .filter { it.platformType == KotlinPlatformType.jvm }
                .filter { includeTests || it.defaultSourceSet.name == "main" }
                .distinctBy { it.defaultSourceSet.name }
                .map { compilation ->
                    val sourceSet = compilation.defaultSourceSet
                    val directories = sourceDirectories(project, sourceSet, includeJavaSources)
                    val dependencyConfiguration =
                        project.configurations.named(
                            compilation.compileDependencyConfigurationName,
                        )
                    val projectDependencyPaths =
                        dependencyConfiguration.map { configuration ->
                            localProjectDependencyPaths(configuration.incoming.resolutionResult)
                        }
                    val associatedModuleIds =
                        compilation.allAssociatedCompilations
                            .mapTo(LinkedHashSet()) { moduleId(project.path, it.defaultSourceSet.name) }
                    val taskName = "klassTreeModuleSpec${sourceSet.name.replaceFirstChar { it.uppercase() }}"
                    val moduleTask =
                        project.tasks.register(taskName, KlassTreeModuleSpecTask::class.java) { task ->
                            task.projectPath.set(project.path)
                            task.sourceSetName.set(sourceSet.name)
                            task.moduleId.set(moduleId(project.path, sourceSet.name))
                            task.moduleName.set(moduleName(project.path, sourceSet.name))
                            task.projectDependencyPaths.set(projectDependencyPaths)
                            task.associatedModuleIds.set(associatedModuleIds)
                            task.sourceDirectories.from(directories)
                            val sourceProjectPaths = task.sourceProjectPaths
                            task.classpath.from(
                                dependencyConfiguration.map { configuration ->
                                    configuration.incoming
                                        .artifactView { view ->
                                            view.componentFilter { id ->
                                                id !is ProjectComponentIdentifier ||
                                                    id.build.buildPath != buildPath ||
                                                    id.projectPath !in sourceProjectPaths.get()
                                            }
                                        }.files
                                },
                            )
                            task.outputFile.set(
                                project.layout.buildDirectory.file("klass-tree/modules/${sourceSet.name}.json"),
                            )
                        }
                    directories to moduleTask
                }
        return KotlinProjectScan(modules.map { it.first }, modules.map { it.second })
    }

    private fun localProjectDependencyPaths(resolutionResult: ResolutionResult): List<String> {
        val rootId = resolutionResult.rootComponent.get().id as ProjectComponentIdentifier
        return resolutionResult.allComponents
            .mapNotNull { it.id as? ProjectComponentIdentifier }
            .filter { it != rootId && it.build.buildPath == rootId.build.buildPath }
            .map { it.projectPath }
            .distinct()
    }

    private fun requireSupportedVersion(project: Project) {
        val version = (project.plugins.getPlugin("org.jetbrains.kotlin.jvm") as KotlinBasePlugin).pluginVersion
        val (major, minor) = version.split('.').take(2).map(String::toInt)
        if (KotlinVersion(major, minor) < minimumKgpVersion) {
            throw GradleException("klass-tree requires Kotlin Gradle plugin 2.3 or newer; detected $version.")
        }
    }

    private fun sourceDirectories(
        project: Project,
        sourceSet: org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet,
        includeJavaSources: Boolean,
    ): FileCollection {
        val javaDirectories =
            if (includeJavaSources) {
                project.extensions
                    .findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
                    ?.sourceSets
                    ?.findByName(sourceSet.name)
                    ?.java
                    ?.sourceDirectories
            } else {
                null
            }
        return project.files(
            listOfNotNull(
                sourceSet.kotlin.sourceDirectories,
                sourceSet.generatedKotlin.sourceDirectories,
                javaDirectories,
            ),
        )
    }

    private fun moduleName(
        projectPath: String,
        sourceSetName: String,
    ): String {
        val projectPart = projectPath.removePrefix(":").ifEmpty { "root" }
        return sanitize(projectPart) + "_" + sanitize(sourceSetName)
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "root" }
}
