package io.github.klasstree.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

/**
 * Root-project plugin writing a source dependency graph for all Kotlin JVM subprojects. Applying
 * it to a non-root project is rejected because source set names and output paths would otherwise
 * be ambiguous.
 */
class KlassTreePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project !== project.rootProject) {
            throw GradleException(
                "The io.github.klass-tree plugin must be applied to the root project, " +
                    "but was applied to '${project.path}'.",
            )
        }

        val extension = project.extensions.create("klassTree", KlassTreeExtension::class.java)

        val analyzerConfiguration = project.configurations.create("klassTreeAnalyzer") { configuration ->
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.description =
                "Kotlin Analysis API used by the klass-tree analyzer process (isolated from the Gradle daemon)."
            configuration.attributes {
                it.attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
                )
                it.attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.objects.named(Category::class.java, Category.LIBRARY),
                )
                it.attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
                )
                it.attribute(
                    Bundling.BUNDLING_ATTRIBUTE,
                    project.objects.named(Bundling::class.java, Bundling.EXTERNAL),
                )
                it.attribute(
                    TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                    project.objects.named(
                        TargetJvmEnvironment::class.java,
                        TargetJvmEnvironment.STANDARD_JVM,
                    ),
                )
            }
        }

        val task = project.tasks.register("klassTree", KlassTreeTask::class.java) { task ->
            task.group = "documentation"
            task.description = "Writes a source-level Kotlin dependency graph (JSON and DOT)."
            task.outputDirectory.set(extension.outputDirectory)
            task.formats.set(extension.formats)
            task.analyzerJvmArgs.set(extension.analyzerJvmArgs)
            task.analysisClasspath.from(analyzerConfiguration)
            task.analyzerClasspath.from(PluginClasspath.resolve())
            task.projectRoot.set(project.rootProject.projectDir.absolutePath)
            task.failOnAnalysisError.set(extension.failOnAnalysisError)
            task.includeTests.set(extension.includeTests)
            task.includeJavaSources.set(extension.includeJavaSources)
        }

        project.tasks.register("klassTreeCheck", KlassTreeCheckTask::class.java) { checkTask ->
            checkTask.group = "verification"
            checkTask.description =
                "Compares graph.json with the baseline and fails on new references to denied targets."
            checkTask.graphJson.set(task.flatMap { it.outputDirectory.file("graph.json") })
            checkTask.baselineFile.set(extension.baselineFile)
            checkTask.baselineInputs.from(extension.baselineFile)
            checkTask.deniedTargets.set(extension.deniedTargets)
            checkTask.diffFile.set(extension.outputDirectory.file("klass-tree-diff.json"))
            checkTask.dependsOn(task)
        }

        val projectScans = mutableMapOf<String, KotlinProjectScan>()
        var scannerError: Throwable? = null
        fun scan(projectToScan: Project) {
            if (!projectToScan.plugins.hasPlugin("org.jetbrains.kotlin.jvm")) return
            try {
                projectScans[projectToScan.path] =
                    KotlinProjectScanner.configureProject(
                        projectToScan,
                        extension.includeTests.get(),
                        extension.includeJavaSources.get(),
                    )
            } catch (error: LinkageError) {
                scannerError = error
            }
        }
        project.allprojects { projectToScan ->
            if (projectToScan.state.executed) {
                scan(projectToScan)
            } else {
                projectToScan.afterEvaluate { scan(projectToScan) }
            }
        }

        project.afterEvaluate {
            project.dependencies.add(
                analyzerConfiguration.name,
                "org.jetbrains.kotlin:kotlin-analysis-api:${extension.analysisApiVersion.get()}",
            )
        }

        project.gradle.projectsEvaluated {
            val formats = extension.formats.get()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (formats.isEmpty() || formats.any { it != "json" && it != "dot" }) {
                throw GradleException(
                    "klassTree.formats must be a non-empty subset of [json, dot], but was " +
                        extension.formats.get(),
                )
            }

            val kotlinProjects = project.rootProject.allprojects.filter {
                it.plugins.hasPlugin("org.jetbrains.kotlin.jvm")
            }
            if (kotlinProjects.isEmpty()) {
                throw GradleException(
                    "klass-tree found no Kotlin JVM projects. Apply the Kotlin JVM plugin to at " +
                        "least one project before running the klassTree task.",
                )
            }

            scannerError?.let { error ->
                throw GradleException(
                    "klass-tree could not access the Kotlin Gradle plugin API (${error.message}). " +
                        "Declare the Kotlin JVM plugin on the root buildscript classpath, for " +
                        "example with `plugins { kotlin(\"jvm\") version \"...\" apply false }`.",
                    error,
                )
            }

            val sourceDirectories = projectScans.values.flatMap { it.sourceDirectories }
            // Wiring module-spec task outputs through their providers also carries the task
            // dependencies, so klassTree runs every producer task first.
            val moduleSpecOutputs = projectScans.values
                .flatMap { it.moduleTasks }
                .map { moduleTask -> moduleTask.flatMap(KlassTreeModuleSpecTask::outputFile) }
            task.configure { klassTreeTask ->
                klassTreeTask.moduleSpecFiles.from(moduleSpecOutputs)
                klassTreeTask.sourceFiles.from(sourceDirectories)
                klassTreeTask.formats.set(formats)
            }
        }
    }
}
