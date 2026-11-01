package io.github.klasstree.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Writes the analysis spec and launches the analyzer in a separate JVM.
 *
 * The Gradle daemon never loads Analysis API or IntelliJ platform classes. [analysisClasspath]
 * contains the isolated `klassTreeAnalyzer` configuration; [analyzerClasspath] contains this
 * plugin's own runtime (analyzer + graph-core + serialization + Kotlin stdlib).
 */
abstract class KlassTreeTask : DefaultTask() {
    @get:Input
    abstract val specJson: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val formats: SetProperty<String>

    @get:Input
    abstract val analyzerJvmArgs: ListProperty<String>

    @get:Classpath
    abstract val analysisClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val analyzerClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun analyze() {
        val output = outputDirectory.get().asFile
        output.mkdirs()
        val specFile = File(output, "analysis-spec.json")
        specFile.writeText(specJson.get())

        val result = execOperations.javaexec { spec ->
            spec.classpath = analysisClasspath + analyzerClasspath
            spec.mainClass.set("io.github.klasstree.analyzer.AnalyzerMainKt")
            spec.args(
                specFile.absolutePath,
                output.absolutePath,
                formats.get().sorted().joinToString(","),
            )
            analyzerJvmArgs.get().forEach(spec::jvmArgs)
        }

        if (result.exitValue != 0) {
            throw GradleException(
                "klass-tree analyzer failed with exit code ${result.exitValue}. " +
                    "See the analyzer output above for details.",
            )
        }
    }
}
