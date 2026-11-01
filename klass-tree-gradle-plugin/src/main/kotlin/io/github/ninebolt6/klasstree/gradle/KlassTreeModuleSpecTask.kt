package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import kotlinx.serialization.builtins.ListSerializer
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class KlassTreeModuleSpecTask : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val sourceSetName: Property<String>

    @get:Input
    abstract val moduleId: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val projectDependencyPaths: ListProperty<String>

    @get:Input
    abstract val associatedModuleIds: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeModuleSpec() {
        val sourceRoots = sourceDirectories.files
            .filter { it.isDirectory }
            .filter { directory ->
                directory.walkTopDown().any { it.isFile && (it.extension == "kt" || it.extension == "java") }
            }
            .map { it.canonicalFile }
            .distinct()
            .sortedBy { it.absolutePath }
            .map { it.absolutePath }
        val candidates = if (sourceRoots.isEmpty()) {
            emptyList()
        } else {
            listOf(
                AnalysisModuleCandidate(
                    projectPath = projectPath.get(),
                    sourceSetName = sourceSetName.get(),
                    moduleId = moduleId.get(),
                    moduleName = moduleName.get(),
                    sourceRoots = sourceRoots,
                    classpath = classpath.files
                        .filter { it.exists() }
                        .map { it.absolutePath }
                        .distinct(),
                    projectDependencyPaths = projectDependencyPaths.get().sorted(),
                    associatedModuleIds = associatedModuleIds.get().sorted(),
                ),
            )
        }
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(KlassTreeJson.encodeToString(ListSerializer(AnalysisModuleCandidate.serializer()), candidates))
    }
}
