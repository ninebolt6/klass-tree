package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.GraphDiff
import io.github.ninebolt6.klasstree.graph.GraphDiffer
import io.github.ninebolt6.klasstree.graph.GraphEdge
import io.github.ninebolt6.klasstree.graph.GraphEdgeDelta
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

/**
 * Compares the current `graph.json` with the committed baseline and fails when new references to
 * `deniedTargets` appear. Always writes the full diff to [diffFile] so CI can archive it.
 */
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
    abstract val deniedTargets: ListProperty<String>

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
        val baseline = decode(baselinePath.readText())
        val current = decode(graphJson.get().asFile.readText())
        val diff = GraphDiffer.diff(baseline, current)
        writeDiff(diff)

        logger.lifecycle(
            "klass-tree: +${diff.addedNodes.size}/-${diff.removedNodes.size} nodes, " +
                "+${diff.addedEdges.size}/-${diff.removedEdges.size} edges " +
                "(${diff.edgeDeltas.size} edge(s) with call-site changes)",
        )

        val targets = deniedTargets.get()
        if (targets.isEmpty()) return

        val nodesById = current.nodes.associateBy { it.id }
        val violations = buildList {
            diff.addedEdges.forEach { edge ->
                if (edge.to.matchesAny(targets, nodesById)) add(describe(edge, nodesById))
            }
            diff.edgeDeltas.forEach { delta ->
                if (delta.to.matchesAny(targets, nodesById) && delta.addedCallSites.isNotEmpty()) {
                    add(describe(delta, nodesById))
                }
            }
        }
        val baselineNodesById = baseline.nodes.associateBy { it.id }
        fun isDeniedTarget(nodeId: String) =
            nodesById[nodeId].matchesAny(targets) || baselineNodesById[nodeId].matchesAny(targets)
        val progress = diff.removedEdges.count { isDeniedTarget(it.to) } +
            diff.edgeDeltas.count { it.removedCallSites.isNotEmpty() && isDeniedTarget(it.to) }
        if (progress > 0) {
            logger.lifecycle("klass-tree: $progress denied reference(s) removed since the baseline")
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "klass-tree: ${violations.size} new reference(s) to denied targets $targets:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun decode(content: String): DependencyGraph =
        KlassTreeJson.decodeFromString(DependencyGraph.serializer(), content)

    private fun writeDiff(diff: GraphDiff) {
        val file = diffFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(KlassTreeJson.encodeToString(GraphDiff.serializer(), diff))
    }

    private fun String.matchesAny(targets: List<String>, nodesById: Map<String, GraphNode>): Boolean =
        nodesById[this].matchesAny(targets)

    private fun GraphNode?.matchesAny(targets: List<String>): Boolean =
        this != null && targets.any { prefix ->
            fqName?.startsWith(prefix) == true || packageName?.startsWith(prefix) == true
        }

    private fun describe(edge: GraphEdge, nodesById: Map<String, GraphNode>): String {
        val sites = edge.callSites.joinToString(", ") { "${it.file}:${it.line}" }
        return "+ ${label(edge.from, nodesById)} -> ${label(edge.to, nodesById)} ${edge.kind}" +
            if (sites.isNotEmpty()) " @ $sites" else ""
    }

    private fun describe(delta: GraphEdgeDelta, nodesById: Map<String, GraphNode>): String {
        val sites = delta.addedCallSites.joinToString(", ") { "${it.file}:${it.line}" }
        return "+ ${label(delta.from, nodesById)} -> ${label(delta.to, nodesById)} ${delta.kind} @ $sites"
    }

    private fun label(nodeId: String, nodesById: Map<String, GraphNode>): String =
        nodesById[nodeId]?.let { it.fqName ?: it.name } ?: nodeId
}
