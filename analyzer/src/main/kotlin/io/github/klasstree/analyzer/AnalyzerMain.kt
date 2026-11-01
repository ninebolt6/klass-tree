package io.github.klasstree.analyzer

import io.github.klasstree.graph.AnalysisSpec
import io.github.klasstree.graph.DependencyGraph
import io.github.klasstree.graph.dot.DotExporter
import io.github.klasstree.graph.JsonExporter
import io.github.klasstree.graph.KlassTreeJson
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
        val graph = Analyzer().analyze(spec, disposeGlobalServices = true)

        Files.createDirectories(outputDirectory)

        // JSON is the primary artifact. DOT is rendered from the serialized JSON so that both
        // outputs cannot drift apart and so other exporters can be added without touching the
        // Analysis API layer.
        val jsonFile = outputDirectory.resolve("graph.json")
        JsonExporter.write(graph, jsonFile)

        if ("dot" in formats) {
            val jsonGraph = KlassTreeJson.decodeFromString<DependencyGraph>(Files.readString(jsonFile))
            DotExporter.write(jsonGraph, outputDirectory.resolve("graph.dot"))
        }

        println(
            "klass-tree: ${graph.stats.nodeCount} nodes, ${graph.stats.edgeCount} edges, " +
                "${graph.stats.callSiteCount} call sites -> $outputDirectory",
        )
        println(
            "klass-tree: candidates=${graph.stats.resolution.candidates}, " +
                "source=${graph.stats.resolution.resolvedSource}, " +
                "external=${graph.stats.resolution.resolvedExternal}, " +
                "unresolved=${graph.stats.resolution.unresolved}, " +
                "skipped=${graph.stats.resolution.skipped}, " +
                "self=${graph.stats.resolution.selfEdgesSkipped}, " +
                "errors=${graph.stats.resolution.analysisErrors.size}",
        )
        graph.stats.resolution.analysisErrors.forEach { println("klass-tree: analysis error: $it") }
        exitProcess(0)
    } catch (throwable: Throwable) {
        throwable.printStackTrace()
        exitProcess(1)
    }
}
