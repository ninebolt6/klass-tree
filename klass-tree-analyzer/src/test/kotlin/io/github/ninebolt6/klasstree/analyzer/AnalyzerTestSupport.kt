package io.github.ninebolt6.klasstree.analyzer

import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.DependencyGraph
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphEdge
import io.github.ninebolt6.klasstree.graph.ModuleSpec
import java.io.File
import java.nio.file.Files

internal fun module(
    id: String,
    vararg sources: Pair<String, String>,
    extraClasspath: List<String> = emptyList(),
    dependencies: List<String> = emptyList(),
) = TestModule(id, sources.toList(), extraClasspath, dependencies)

internal data class TestModule(
    val id: String,
    val sources: List<Pair<String, String>>,
    val extraClasspath: List<String>,
    val dependencies: List<String>,
)

internal fun analyze(
    vararg modules: TestModule,
    includeJavaSources: Boolean = true,
): AnalysisResult {
    val root = Files.createTempDirectory("klass-tree-analyzer-test")
    try {
        val spec =
            AnalysisSpec(
                projectRoot = root.toString(),
                includeJavaSources = includeJavaSources,
                modules =
                    modules.map { module ->
                        val sourceSetName = module.id.substringAfterLast(":")
                        val projectPath = module.id.substringBeforeLast(":").ifEmpty { ":" }
                        val label = projectPath.removePrefix(":").ifEmpty { "root" }
                        val sourceRoot = root.resolve("src/$label/$sourceSetName")
                        module.sources.forEach { (path, content) ->
                            val file = sourceRoot.resolve(path)
                            Files.createDirectories(file.parent)
                            file.toFile().writeText(content.trimIndent())
                        }
                        ModuleSpec(
                            id = module.id,
                            projectPath = projectPath,
                            sourceSetName = sourceSetName,
                            moduleName = "${label}_$sourceSetName".replace(Regex("[^A-Za-z0-9_]"), "_"),
                            sourceRoots = listOf(sourceRoot.toString()),
                            classpath = listOf(stdlib()) + module.extraClasspath,
                            dependencies = module.dependencies,
                        )
                    },
            )
        return Analyzer().analyze(spec)
    } finally {
        root.toFile().deleteRecursively()
    }
}

internal fun edge(
    graph: DependencyGraph,
    fromFqName: String,
    toFqName: String,
    kind: EdgeKind,
): GraphEdge? {
    val from = graph.nodes.single { it.fqName == fromFqName }
    val to = graph.nodes.single { it.fqName == toFqName }
    return graph.edges.singleOrNull {
        it.from == from.id && it.to == to.id && it.kind == kind
    }
}

internal fun classpathEntries(): List<String> = System.getProperty("java.class.path").split(File.pathSeparator)

internal fun stdlib(): String = classpathEntries().first { it.contains("kotlin-stdlib") }
