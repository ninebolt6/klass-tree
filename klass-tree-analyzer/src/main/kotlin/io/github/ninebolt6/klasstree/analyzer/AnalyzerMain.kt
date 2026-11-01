package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.dot.DotExporter
import io.github.ninebolt6.klasstree.graph.JsonExporter
import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point used by the Gradle plugin. The process is intentionally short-lived: Analysis API
 * Standalone installs IntelliJ platform services that are awkward to unload inside a Gradle
 * daemon, so the plugin forks this main class with an isolated classpath.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: klass-tree-analyzer <analysis-spec.json> <output-dir> [json,dot]")
        exitProcess(2)
    }

    val specFile = Path.of(args[0])
    val outputDirectory = Path.of(args[1])
    val formats = args.getOrNull(2)
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: setOf("json", "dot")

    try {
        val spec = KlassTreeJson.decodeFromString<AnalysisSpec>(Files.readString(specFile))
        val result = Analyzer().analyze(spec, disposeGlobalServices = true)
        val graph = result.graph

        Files.createDirectories(outputDirectory)

        // JSON is the primary artifact. DOT is rendered from the serialized JSON so that both
        // outputs cannot drift apart and so other exporters can be added without touching the
        // Analysis API layer.
        val jsonFile = outputDirectory.resolve("graph.json")
        JsonExporter.write(graph, jsonFile)

        if ("dot" in formats) {
            val jsonGraph = KlassTreeJson.decodeFromString<DependencyGraph>(Files.readString(jsonFile))
            DotExporter.write(jsonGraph, outputDirectory.resolve("graph.dot"))
        } else {
            // A graph.dot produced by an earlier run must not linger and misrepresent the graph.
            Files.deleteIfExists(outputDirectory.resolve("graph.dot"))
        }

        println(
            "klass-tree: ${result.stats.nodeCount} nodes, ${result.stats.edgeCount} edges, " +
                "${result.stats.callSiteCount} call sites -> $outputDirectory",
        )
        println(
            "klass-tree: candidates=${result.stats.resolution.candidates}, " +
                "source=${result.stats.resolution.resolvedSource}, " +
                "external=${result.stats.resolution.resolvedExternal}, " +
                "unresolved=${result.stats.resolution.unresolved}, " +
                "skipped=${result.stats.resolution.skipped}, " +
                "self=${result.stats.resolution.selfEdgesSkipped}, " +
                "errors=${result.stats.resolution.analysisErrors.size}",
        )
        result.stats.resolution.analysisErrors.forEach { println("klass-tree: analysis error: $it") }
        exitProcess(0)
    } catch (throwable: Throwable) {
        throwable.printStackTrace()
        exitProcess(1)
    }
}
