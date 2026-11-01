package io.github.klasstree.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

/**
 * Configuration for the `klassTree` task.
 *
 * The extension is registered on the root project. It intentionally exposes only Gradle types so
 * that the plugin can be configured from either the Kotlin or Groovy DSL.
 */
abstract class KlassTreeExtension @Inject constructor(layout: ProjectLayout) {
    /** Analyze test source sets in addition to `main`. */
    abstract val includeTests: Property<Boolean>

    /** Analyze `.java` sources alongside `.kt`. Disable to skip Java entirely. */
    abstract val includeJavaSources: Property<Boolean>

    /** Output formats. `json` is always written; `dot` is derived from the JSON output. */
    abstract val formats: SetProperty<String>

    /** Directory receiving `graph.json`, `graph.dot` and the intermediate analysis spec. */
    abstract val outputDirectory: DirectoryProperty

    /** Version of `org.jetbrains.kotlin:kotlin-analysis-api` used by the forked analyzer. */
    abstract val analysisApiVersion: Property<String>

    /** Fail the build when at least one file could not be analyzed. */
    abstract val failOnAnalysisError: Property<Boolean>

    /** Extra JVM arguments for the forked analyzer process. */
    abstract val analyzerJvmArgs: ListProperty<String>

    /**
     * Committed baseline graph that `klassTreeCheck` compares against. Produce it by running
     * `klassTree` and checking the resulting `graph.json` into version control under this name.
     */
    abstract val baselineFile: RegularFileProperty

    /**
     * fqName/package prefixes that must not gain new references relative to the baseline, for
     * example `com.example.legacy`. Only edges or call sites absent from the baseline count as
     * violations, so the baseline freezes the current state while migration shrinks it.
     */
    abstract val deniedTargets: ListProperty<String>

    init {
        includeTests.convention(false)
        includeJavaSources.convention(true)
        formats.convention(setOf("json", "dot"))
        outputDirectory.convention(layout.buildDirectory.dir("klass-tree"))
        baselineFile.convention(layout.projectDirectory.file("klass-tree-baseline.json"))
        analysisApiVersion.convention(DEFAULT_ANALYSIS_API_VERSION)
        failOnAnalysisError.convention(false)
        analyzerJvmArgs.convention(emptyList())
        deniedTargets.convention(emptyList())
    }

    companion object {
        const val DEFAULT_ANALYSIS_API_VERSION = "2.5.0-Beta2"
    }
}
