package io.github.klasstree.graph

import java.nio.file.Files
import java.nio.file.Path

object JsonExporter {
    fun render(graph: DependencyGraph): String =
        KlassTreeJson.encodeToString(DependencyGraph.serializer(), graph)

    fun write(graph: DependencyGraph, target: Path) {
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, render(graph))
    }
}
