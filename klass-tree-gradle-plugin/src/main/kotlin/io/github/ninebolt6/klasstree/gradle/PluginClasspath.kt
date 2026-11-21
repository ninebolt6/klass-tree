package io.github.ninebolt6.klasstree.gradle

import java.io.File

// Gradle classloaders include compiler jars that conflict with the standalone analyzer.
internal object PluginClasspath {
    fun resolve(): List<File> {
        val loader = KlassTreePlugin::class.java.classLoader
        val files = LinkedHashSet<File>()

        val representativeClasses =
            listOfNotNull(
                io.github.ninebolt6.klasstree.graph.AnalysisSpec::class.java,
                kotlinx.serialization.json.Json::class.java,
                kotlinx.serialization.KSerializer::class.java,
                runCatching {
                    Class.forName("io.github.ninebolt6.klasstree.analyzer.AnalyzerMainKt", false, loader)
                }.getOrNull(),
            )
        representativeClasses.forEach { type ->
            type.protectionDomain?.codeSource?.location?.let { location ->
                try {
                    files += File(location.toURI())
                } catch (_: Exception) {
                }
            }
        }

        return files
            .filter { it.exists() }
            .sortedBy { it.absolutePath }
    }
}
