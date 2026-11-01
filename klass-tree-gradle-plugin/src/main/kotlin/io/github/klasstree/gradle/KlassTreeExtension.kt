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
    abstract val includeTests: Property<Boolean>
    abstract val includeJavaSources: Property<Boolean>

    /** `json` is always written; `dot` is derived from the JSON output. */
    abstract val formats: SetProperty<String>

    abstract val outputDirectory: DirectoryProperty
    abstract val analysisApiVersion: Property<String>
    abstract val failOnAnalysisError: Property<Boolean>
    abstract val analyzerJvmArgs: ListProperty<String>

    /**
     * Committed baseline graph that `klassTreeCheck` compares against; written by
     * `klassTreeBaseline` and checked into version control.
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
