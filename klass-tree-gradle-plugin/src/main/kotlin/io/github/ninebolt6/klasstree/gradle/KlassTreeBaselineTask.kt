package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.BaselineRatchet
import io.github.ninebolt6.klasstree.graph.DependencyBaseline
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class KlassTreeBaselineTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val graphJson: RegularFileProperty

    @get:OutputFile
    abstract val baselineFile: RegularFileProperty

    @TaskAction
    fun writeBaseline() {
        val target = baselineFile.get().asFile
        target.parentFile?.mkdirs()
        val graph = KlassTreeJson.decodeFromString(DependencyGraph.serializer(), graphJson.get().asFile.readText())
        target.writeText(KlassTreeJson.encodeToString(DependencyBaseline.serializer(), BaselineRatchet.baseline(graph)))
        logger.lifecycle("klass-tree: baseline written to $target")
    }
}
