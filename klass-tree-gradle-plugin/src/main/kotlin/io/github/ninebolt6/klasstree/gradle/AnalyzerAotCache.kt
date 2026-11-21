package io.github.ninebolt6.klasstree.gradle

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

internal object AnalyzerAotCache {
    const val MIN_JAVA_VERSION = 25

    fun arguments(
        directory: File?,
        classpath: List<File>,
        javaVersion: Int,
        javaIdentity: List<String>,
        jvmArgs: List<String>,
    ): List<String> {
        if (directory == null || javaVersion < MIN_JAVA_VERSION) return emptyList()

        val digest = MessageDigest.getInstance("SHA-256")

        fun add(value: String) = digest.update((value + '\u0000').toByteArray(Charsets.UTF_8))

        javaIdentity.forEach(::add)
        jvmArgs.forEach(::add)
        classpath.forEach { entry ->
            val root = entry.toPath().toAbsolutePath().normalize()
            add(root.toString())
            if (Files.isDirectory(root)) {
                Files.walk(root).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                        add(root.relativize(file).toString())
                        add(Files.size(file).toString())
                        add(Files.getLastModifiedTime(file).toString())
                    }
                }
            } else {
                add(Files.size(root).toString())
                add(Files.getLastModifiedTime(root).toString())
            }
        }
        Files.createDirectories(directory.toPath())
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val cache = File(directory, "analyzer-$hash.aot").absoluteFile
        val option = if (cache.isFile) "AOTCache" else "AOTCacheOutput"
        return listOf("-XX:AOTMode=auto", "-XX:$option=$cache")
    }
}
