package io.github.klasstree.gradle

import java.io.File
import java.net.URLClassLoader

/**
 * Resolves the runtime classpath of this plugin.
 *
 * Gradle loads plugins from a classloader that usually exposes its classpath as URLs. As a
 * fallback (and to cover nested classloaders) the code sources of a few representative classes
 * are added. Analysis API itself is not part of this classpath; it is supplied separately by the
 * `klassTreeAnalyzer` configuration.
 */
internal object PluginClasspath {
    fun resolve(): List<File> {
        val loader = KlassTreePlugin::class.java.classLoader
        val files = LinkedHashSet<File>()

        var current: ClassLoader? = loader
        while (current != null) {
            if (current is URLClassLoader) {
                current.urLs.forEach { url ->
                    if (url.protocol == "file") {
                        try {
                            files += File(url.toURI())
                        } catch (_: Exception) {
                            // Ignore malformed classpath entries; they cannot be used by the fork.
                        }
                    }
                }
            }
            current = current.parent
        }

        val representativeClasses = listOfNotNull(
            KlassTreePlugin::class.java,
            io.github.klasstree.graph.AnalysisSpec::class.java,
            kotlinx.serialization.json.Json::class.java,
            runCatching {
                Class.forName("io.github.klasstree.analyzer.AnalyzerMainKt", false, loader)
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
