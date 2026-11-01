package io.github.klasstree.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configuration for the `klassTree` task.
 *
 * The extension is registered on the root project. It intentionally exposes only Gradle types so
 * that the plugin can be configured from either the Kotlin or Groovy DSL.
 */
abstract class KlassTreeExtension {
    /** Analyze test source sets in addition to `main`. */
    abstract val includeTests: Property<Boolean>

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

    init {
        includeTests.convention(false)
        formats.convention(setOf("json", "dot"))
        analysisApiVersion.convention(DEFAULT_ANALYSIS_API_VERSION)
        failOnAnalysisError.convention(false)
        analyzerJvmArgs.convention(emptyList())
    }

    companion object {
        const val DEFAULT_ANALYSIS_API_VERSION = "2.5.0-Beta2"
    }
}
