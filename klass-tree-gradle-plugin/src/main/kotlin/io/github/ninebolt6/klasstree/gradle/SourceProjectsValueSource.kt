package io.github.ninebolt6.klasstree.gradle

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

internal abstract class SourceProjectsValueSource : ValueSource<List<String>, SourceProjectsValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val sourceDirectories: MapProperty<String, List<File>>
    }

    override fun obtain(): List<String> =
        parameters.sourceDirectories
            .get()
            .filterValues { sourceRoots(it).isNotEmpty() }
            .keys
            .toList()
}

internal fun sourceRoots(directories: Collection<File>): List<String> =
    directories
        .filter { it.isDirectory }
        .filter { directory ->
            directory.walkTopDown().any { it.isFile && (it.extension == "kt" || it.extension == "java") }
        }.map { it.canonicalPath }
        .distinct()
        .sorted()
