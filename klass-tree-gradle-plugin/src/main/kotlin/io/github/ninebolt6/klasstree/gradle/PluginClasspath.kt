package io.github.ninebolt6.klasstree.gradle

import java.io.File

/**
 * Resolves the runtime code sources needed by the forked analyzer.
 *
 * Only the jars of a few known classes are used. Walking the plugin classloader hierarchy would pull
 * in the whole Gradle distribution, including its own Kotlin compiler and stdlib. Analysis API and
 * Kotlin stdlib are supplied separately by the `klassTreeAnalyzer` configuration.
 */
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
                runCatching {
                    Class.forName("io.github.ninebolt6.klasstree.graph.dot.DotExporter", false, loader)
                }.getOrNull(),
            )
        representativeClasses.forEach { type ->
            type.protectionDomain?.codeSource?.location?.let { location ->
                try {
                    files += File(location.toURI())
                } catch (_: Exception) {
                    // Ignore code sources that are not local files.
                }
            }
        }

        return files
            .filter { it.exists() }
            .sortedBy { it.absolutePath }
    }
}
