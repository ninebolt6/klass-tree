package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.BaselineComparison
import io.github.ninebolt6.klasstree.graph.BaselineRatchet
import io.github.ninebolt6.klasstree.graph.DeniedDependency
import io.github.ninebolt6.klasstree.graph.DependencyBaseline
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.EdgeCountChange
import io.github.ninebolt6.klasstree.graph.GraphEdge
import io.github.ninebolt6.klasstree.graph.GraphNode
import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "Dependency checks are inexpensive")
abstract class KlassTreeCheckTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val graphJson: RegularFileProperty

    @get:Internal
    abstract val baselineFile: RegularFileProperty

    /** Tracks the baseline's content; a missing baseline is reported by the action, not validation. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baselineInputs: ConfigurableFileCollection

    @get:Input
    abstract val deniedDependencies: ListProperty<DeniedDependency>

    @get:OutputFile
    abstract val diffFile: RegularFileProperty

    @TaskAction
    fun check() {
        val baselinePath = baselineFile.get().asFile
        if (!baselinePath.isFile) {
            throw GradleException(
                "klass-tree: no baseline graph at $baselinePath. Run `./gradlew " +
                    "klassTreeBaseline` and commit the result, or set klassTree.baselineFile.",
            )
        }
        val baseline = decodeBaseline(baselinePath)
        val current = decodeGraph(graphJson.get().asFile)
        val diff = BaselineRatchet.compare(baseline, current, deniedDependencies.get())
        val file = diffFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(KlassTreeJson.encodeToString(BaselineComparison.serializer(), diff))

        logger.lifecycle("klass-tree: ${diff.changes.size} edge(s) with call-site count changes")
        val progress = diff.progress.sumOf { it.baseline - it.current }
        if (progress > 0) {
            logger.lifecycle("klass-tree: $progress denied reference(s) removed since the baseline")
        }
        if (diff.violations.isNotEmpty()) {
            val nodes = current.nodes.associateBy { it.id }
            val edges = current.edges.associateBy { Triple(it.from, it.to, it.kind) }
            val violations =
                diff.violations.map { change ->
                    val edge = edges.getValue(Triple(change.from, change.to, change.kind))
                    describe(change, edge, nodes)
                }
            val added = diff.violations.sumOf { it.current - it.baseline }
            val rules = deniedDependencies.get().joinToString { "${it.from ?: "*"} -> ${it.to}" }
            throw GradleException(
                "klass-tree: $added new reference(s) violating [$rules]:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun decodeBaseline(file: File): DependencyBaseline =
        try {
            KlassTreeJson.decodeFromString(DependencyBaseline.serializer(), file.readText())
        } catch (exception: IllegalArgumentException) {
            throw GradleException("klass-tree: cannot parse $file: ${exception.message}", exception)
        }

    private fun decodeGraph(file: File): DependencyGraph =
        try {
            KlassTreeJson.decodeFromString(DependencyGraph.serializer(), file.readText())
        } catch (exception: IllegalArgumentException) {
            throw GradleException("klass-tree: cannot parse $file: ${exception.message}", exception)
        }

    private fun describe(
        change: EdgeCountChange,
        edge: GraphEdge,
        nodesById: Map<String, GraphNode>,
    ): String {
        val sites = edge.callSites.joinToString(", ") { "${it.file}:${it.line}" }
        return "+ ${label(change.from, nodesById)} -> ${label(change.to, nodesById)} ${change.kind}" +
            " (${change.baseline} -> ${change.current} call sites)" +
            if (sites.isNotEmpty()) " @ $sites" else ""
    }

    private fun label(
        nodeId: String,
        nodesById: Map<String, GraphNode>,
    ): String = nodesById[nodeId]?.let { it.fqName ?: it.name } ?: nodeId
}
