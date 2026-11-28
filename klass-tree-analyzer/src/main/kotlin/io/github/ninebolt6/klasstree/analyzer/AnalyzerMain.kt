package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.JsonExporter
import io.github.ninebolt6.klasstree.graph.KlassTreeJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

    val summaryFile = outputDirectory.resolve("analysis-summary.json")
    JsonExporter.write(graph, outputDirectory.resolve("graph.json"))
    writeAnalysisSummary(result, summaryFile)

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
    println("klass-tree: analysis summary -> $summaryFile")
}

private fun writeAnalysisSummary(
    result: AnalysisResult,
    target: Path,
) {
    val resolution = result.stats.resolution
    val summary =
        buildJsonObject {
            put("schemaVersion", 1)
            put("nodes", result.stats.nodeCount)
            put("edges", result.stats.edgeCount)
            put("callSites", result.stats.callSiteCount)
            put("sourceFiles", result.stats.sourceFileCount)
            put("elapsedMillis", result.stats.elapsedMillis)
            putJsonObject("resolution") {
                put("candidates", resolution.candidates)
                put("resolvedSource", resolution.resolvedSource)
                put("resolvedExternal", resolution.resolvedExternal)
                put("unresolved", resolution.unresolved)
                put("skipped", resolution.skipped)
                put("selfEdgesSkipped", resolution.selfEdgesSkipped)
                put("analysisErrors", resolution.analysisErrors.size)
                putJsonArray("analysisErrorSamples") {
                    resolution.analysisErrors.take(MAX_ERROR_SAMPLES).forEach { add(it) }
                }
                putJsonObject("unresolvedByKind") {
                    resolution.unresolvedByKind.forEach { (kind, count) -> put(kind, count) }
                }
                putJsonArray("unresolvedSamples") {
                    resolution.unresolvedSamples.forEach { sample ->
                        add(
                            buildJsonObject {
                                put("kind", sample.kind)
                                sample.file?.let { put("file", it) }
                                sample.line?.let { put("line", it) }
                                sample.column?.let { put("column", it) }
                                put("text", sample.text)
                            },
                        )
                    }
                }
            }
        }
    Files.writeString(target, KlassTreeJson.encodeToString(JsonObject.serializer(), summary))
}

private const val MAX_ERROR_SAMPLES = 200
