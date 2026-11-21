package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import kotlinx.serialization.builtins.ListSerializer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Aggregates module specs and launches the analyzer in a separate JVM.
 *
 * The Gradle daemon never loads Analysis API or IntelliJ platform classes. [analysisClasspath]
 * contains the isolated `klassTreeAnalyzer` configuration; [analyzerClasspath] contains the
 * analyzer and its graph/serialization runtime.
 */
abstract class KlassTreeTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleSpecFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val projectRoot: Property<String>

    @get:Input
    abstract val failOnAnalysisError: Property<Boolean>

    @get:Input
    abstract val includeTests: Property<Boolean>

    @get:Input
    abstract val includeJavaSources: Property<Boolean>

    @get:Input
    abstract val formats: SetProperty<String>

    @get:Input
    abstract val analyzerJvmArgs: ListProperty<String>

    @get:Internal
    abstract val aotCache: Property<Boolean>

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
        val candidates =
            moduleSpecFiles.files.flatMap { file ->
                KlassTreeJson.decodeFromString(
                    ListSerializer(AnalysisModuleCandidate.serializer()),
                    file.readText(),
                )
            }
        val modules = ModuleSpecs.build(candidates)
        if (modules.isEmpty()) {
            throw GradleException(
                "klass-tree found no Kotlin source roots in the analyzed projects. " +
                    "Check the Kotlin source set layout" +
                    if (!includeTests.get()) " or enable klassTree.includeTests." else ".",
            )
        }

        logger.lifecycle("klass-tree: analyzing ${modules.size} module(s)")
        modules.forEach { module ->
            logger.info(
                "klass-tree: module ${module.id} (${module.sourceRoots.size} source root(s), " +
                    "${module.classpath.size} classpath entries)",
            )
        }

        val spec =
            AnalysisSpec(
                projectRoot = projectRoot.get(),
                modules = modules,
                failOnAnalysisError = failOnAnalysisError.get(),
                includeJavaSources = includeJavaSources.get(),
            )
        val output = outputDirectory.get().asFile
        output.mkdirs()
        val specFile = File(output, "analysis-spec.json")
        specFile.writeText(KlassTreeJson.encodeToString(AnalysisSpec.serializer(), spec))

        val runtimeClasspath = analysisClasspath + analyzerClasspath
        val aotArgs = aotJvmArguments(runtimeClasspath.files.toList())

        val result =
            execOperations.javaexec { javaexec ->
                javaexec.classpath = runtimeClasspath
                javaexec.mainClass.set("io.github.ninebolt6.klasstree.analyzer.AnalyzerMainKt")
                javaexec.args(
                    specFile.absolutePath,
                    output.absolutePath,
                    formats.get().sorted().joinToString(","),
                )
                analyzerJvmArgs.get().forEach(javaexec::jvmArgs)
                javaexec.jvmArgs(aotArgs)
                javaexec.isIgnoreExitValue = true
            }

        if (result.exitValue != 0) {
            throw GradleException(
                "klass-tree analyzer failed with exit code ${result.exitValue}. " +
                    "See the analyzer output above for details.",
            )
        }
    }

    private fun aotJvmArguments(classpath: List<File>): List<String> {
        if (!aotCache.getOrElse(false)) return emptyList()
        val javaVersion = JavaVersion.current().majorVersion.toInt()
        return if (javaVersion < AnalyzerAotCache.MIN_JAVA_VERSION) {
            logger.warn("klass-tree: aotCache requires Java 25 or newer; running without an AOT cache.")
            emptyList()
        } else if (AnalyzerAotCache.hasConflictingArguments(analyzerJvmArgs.get())) {
            logger.warn("klass-tree: analyzerJvmArgs configures CDS or AOT; ignoring aotCache.")
            emptyList()
        } else {
            try {
                AnalyzerAotCache.arguments(
                    File(projectRoot.get(), ".gradle/klass-tree/aot"),
                    classpath,
                    javaVersion,
                    listOf("java.runtime.version", "java.vendor", "java.home").map { System.getProperty(it) },
                    analyzerJvmArgs.get(),
                )
            } catch (failure: IOException) {
                logger.warn("klass-tree: cannot prepare AOT cache; running without it: ${failure.message}")
                emptyList()
            }
        }
    }
}
