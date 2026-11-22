package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.DeniedDependency
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import javax.inject.Inject

abstract class KlassTreeExtension
    @Inject
    constructor(
        layout: ProjectLayout,
    ) {
        abstract val includeTests: Property<Boolean>
        abstract val includeJavaSources: Property<Boolean>

        abstract val formats: SetProperty<String>

        abstract val outputDirectory: DirectoryProperty
        abstract val analysisApiVersion: Property<String>
        abstract val failOnAnalysisError: Property<Boolean>
        abstract val analyzerJvmArgs: ListProperty<String>
        abstract val aotCache: Property<Boolean>

        abstract val baselineFile: RegularFileProperty

        abstract val deniedDependencies: ListProperty<DeniedDependency>

        fun deny(
            from: String? = null,
            to: String,
        ) {
            deniedDependencies.add(DeniedDependency(from, to))
        }

        init {
            includeTests.convention(false)
            includeJavaSources.convention(true)
            formats.convention(setOf("json", "dot"))
            outputDirectory.convention(layout.buildDirectory.dir("klass-tree"))
            baselineFile.convention(layout.projectDirectory.file("klass-tree-baseline.json"))
            analysisApiVersion.convention(DEFAULT_ANALYSIS_API_VERSION)
            failOnAnalysisError.convention(false)
            analyzerJvmArgs.convention(emptyList())
            aotCache.convention(false)
            deniedDependencies.convention(emptyList())
        }

        companion object {
            const val DEFAULT_ANALYSIS_API_VERSION = "2.5.0-Beta2"
        }
    }
