package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.JsonExporter
import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

// Analysis API Standalone installs global IntelliJ services that cannot safely share the Gradle daemon.
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: klass-tree-analyzer <analysis-spec.json> <output-dir>")
        exitProcess(2)
    }

    val specFile = Path.of(args[0])
    val outputDirectory = Path.of(args[1])

    val failure =
        runCatching {
            run(specFile, outputDirectory)
        }.exceptionOrNull()
    if (failure != null) {
        System.err.println(failure.stackTraceToString())
    }
    exitProcess(if (failure == null) 0 else 1)
}

private fun run(
    specFile: Path,
    outputDirectory: Path,
) {
    val spec = KlassTreeJson.decodeFromString<AnalysisSpec>(Files.readString(specFile))
    val result = Analyzer().analyze(spec, disposeGlobalServices = true)
    val graph = result.graph

    Files.createDirectories(outputDirectory)

    JsonExporter.write(graph, outputDirectory.resolve("graph.json"))

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
    result.stats.resolution.analysisErrors
        .forEach { println("klass-tree: analysis error: $it") }
}
