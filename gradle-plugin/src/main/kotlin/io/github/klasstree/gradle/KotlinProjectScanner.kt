package io.github.klasstree.gradle

import io.github.klasstree.graph.ModuleSpec
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File

/**
 * One Kotlin source set that may become an analysis module. The compile classpath is resolved
 * while the owning project is still being evaluated, which is required by Gradle 9's configuration
 * locking rules.
 */
internal data class AnalysisModuleCandidate(
    val projectPath: String,
    val sourceSetName: String,
    val moduleId: String,
    val moduleName: String,
    val sourceRoots: List<File>,
    val classpath: List<File>,
    val outputFiles: List<File>,
    val projectDependencyPaths: Set<String>,
    val associatedModuleIds: Set<String>,
)

/**
 * Translates the Kotlin JVM Gradle model into analysis modules.
 *
 * This class is deliberately separate from [KlassTreePlugin]: it references Kotlin Gradle plugin
 * API types and is only loaded when the consuming build actually applies the Kotlin JVM plugin.
 * If the Kotlin plugin is not on the root buildscript classpath, the plugin reports a clear error
 * instead of failing with a class-loading exception in the Gradle daemon.
 */
@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal object KotlinProjectScanner {
    fun collectProject(project: Project, extension: KlassTreeExtension): List<AnalysisModuleCandidate> {
        if (!project.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) return emptyList()
        val kotlin = project.extensions.findByType(KotlinJvmExtension::class.java) ?: return emptyList()
        val includeTests = extension.includeTests.get()
        val candidates = mutableListOf<AnalysisModuleCandidate>()
        val seenSourceSets = mutableSetOf<String>()

        kotlin.target.compilations.forEach { compilation ->
            if (compilation.platformType != KotlinPlatformType.jvm) return@forEach
            val sourceSet = compilation.defaultSourceSet
            if (!includeTests && sourceSet.name != "main") return@forEach
            if (!seenSourceSets.add(sourceSet.name)) return@forEach

            val sourceRoots = sourceDirectories(sourceSet)
            if (sourceRoots.isEmpty()) return@forEach

            candidates += AnalysisModuleCandidate(
                projectPath = project.path,
                sourceSetName = sourceSet.name,
                moduleId = moduleId(project.path, sourceSet.name),
                moduleName = moduleName(project.path, sourceSet.name),
                sourceRoots = sourceRoots,
                classpath = compilation.compileDependencyFiles.files.toList(),
                outputFiles = compilation.output.allOutputs.files.toList(),
                projectDependencyPaths = projectDependencies(project, compilation),
                associatedModuleIds = associatedModuleIds(project, compilation),
            )
        }
        return candidates
    }

    fun buildModules(candidates: List<AnalysisModuleCandidate>): List<ModuleSpec> {
        if (candidates.isEmpty()) return emptyList()

        val includedIds = candidates.mapTo(LinkedHashSet()) { it.moduleId }
        // Classpath entries repeat heavily across modules. Canonicalization touches the file
        // system (and resolves symlinks), so do it once per distinct file.
        val canonicalFiles = HashMap<File, File>()
        fun canonical(file: File): File = canonicalFiles.getOrPut(file) { file.canonicalFile }
        val includedOutputs = candidates
            .flatMap { it.outputFiles }
            .mapTo(LinkedHashSet()) { canonical(it) }
        val moduleNameCounts = mutableMapOf<String, Int>()

        return candidates.map { candidate ->
            val dependencyIds = LinkedHashSet<String>()
            dependencyIds += candidate.associatedModuleIds
            candidate.projectDependencyPaths.mapTo(dependencyIds) { moduleId(it, "main") }
            if (candidate.sourceSetName != "main") {
                dependencyIds += moduleId(candidate.projectPath, "main")
            }

            val count = moduleNameCounts.merge(candidate.moduleName, 1, Int::plus) ?: 1
            val uniqueModuleName = if (count == 1) candidate.moduleName else "${candidate.moduleName}_$count"

            ModuleSpec(
                id = candidate.moduleId,
                projectPath = candidate.projectPath,
                sourceSetName = candidate.sourceSetName,
                moduleName = uniqueModuleName,
                sourceRoots = candidate.sourceRoots.map { it.absolutePath },
                classpath = candidate.classpath
                    .filterNot { canonical(it) in includedOutputs }
                    .filter { it.exists() }
                    .map { it.absolutePath }
                    .distinct(),
                dependencies = dependencyIds
                    .filter { it != candidate.moduleId && it in includedIds }
                    .sorted(),
            )
        }.sortedBy { it.id }
    }

    private fun sourceDirectories(sourceSet: KotlinSourceSet): List<File> {
        val directories = LinkedHashSet<File>()
        directories += sourceSet.kotlin.srcDirs
        sourceSet.generatedKotlin.srcDirs.forEach { directories += it }
        return directories
            .filter { it.isDirectory }
            .filter { directory -> directory.walkTopDown().any { it.isFile && it.extension == "kt" } }
            .map { it.canonicalFile }
            .distinct()
            .sortedBy { it.absolutePath }
    }

    private fun projectDependencies(project: Project, compilation: KotlinCompilation<*>): Set<String> {
        val configuration = project.configurations.findByName(compilation.compileDependencyConfigurationName)
            ?: return emptySet()
        return configuration.allDependencies
            .filterIsInstance<ProjectDependency>()
            .mapTo(LinkedHashSet()) { it.path }
    }

    private fun associatedModuleIds(project: Project, compilation: KotlinCompilation<*>): Set<String> =
        compilation.allAssociatedCompilations
            .mapTo(LinkedHashSet()) { moduleId(project.path, it.defaultSourceSet.name) }

    private fun moduleId(projectPath: String, sourceSetName: String): String =
        (if (projectPath == ":") ":" else "$projectPath:") + sourceSetName

    private fun moduleName(projectPath: String, sourceSetName: String): String {
        val projectPart = projectPath.removePrefix(":").ifEmpty { "root" }
        return sanitize(projectPart) + "_" + sanitize(sourceSetName)
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "root" }
}
