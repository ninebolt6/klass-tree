package io.github.ninebolt6.klasstree.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AnalyzerAotCacheTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `older Java does not create a cache directory`() {
        assertEquals(emptyList(), arguments(version = 21))
        assertFalse(root.resolve("cache").toFile().exists())
    }

    @Test
    fun `explicit CDS or AOT arguments disable automatic caching`() {
        val conflicts =
            listOf(
                "-Xshare:off",
                "-Xshare:auto",
                "-Xshare:on",
                "-Xshare:dump",
                "-XX:SharedArchiveFile=custom.jsa",
                "-XX:SharedClassListFile=classes.lst",
                "-XX:DumpLoadedClassList=classes.lst",
                "-XX:AOTCache=custom.aot",
                "-XX:AOTCacheOutput=custom.aot",
                "-XX:AOTMode=auto",
            )
        conflicts.forEach { argument ->
            assertTrue(AnalyzerAotCache.hasConflictingArguments(listOf("-Xmx2g", argument)))
            assertEquals(emptyList(), arguments(jvmArgs = listOf(argument)))
        }
        assertFalse(root.resolve("cache").toFile().exists())
        assertFalse(AnalyzerAotCache.hasConflictingArguments(listOf("-Xmx2g", "-Xlog:aot")))
    }

    @Test
    fun `Java 25 trains then uses the same cache with optional loading`() {
        val training = arguments()
        assertEquals("-XX:AOTMode=auto", training.first())
        assertTrue(training.last().startsWith("-XX:AOTCacheOutput="))
        val cache = File(training.last().substringAfter('='))
        cache.writeBytes(byteArrayOf())

        assertEquals(listOf("-XX:AOTMode=auto", "-XX:AOTCache=$cache"), arguments())
    }

    @Test
    fun `runtime identity and JVM arguments invalidate the cache`() {
        val initial = arguments()
        assertNotEquals(initial, arguments(identity = listOf("25.0.1", "vendor", "/jdk")))
        assertNotEquals(initial, arguments(identity = listOf("25", "other vendor", "/jdk")))
        assertNotEquals(initial, arguments(identity = listOf("25", "vendor", "/other-jdk")))
        assertNotEquals(initial, arguments(jvmArgs = listOf("-Xmx2g")))
    }

    @Test
    fun `classpath membership sizes and order invalidate the cache`() {
        val first = root.resolve("first.jar").toFile().apply { writeText("first") }
        val second = root.resolve("second.jar").toFile().apply { writeText("second") }
        val initial = arguments(classpath = listOf(first, second))
        assertNotEquals(initial, arguments(classpath = listOf(second, first)))
        assertNotEquals(initial, arguments(classpath = listOf(first)))
        val timestamp = Files.getLastModifiedTime(first.toPath())
        first.writeText("different length")
        Files.setLastModifiedTime(first.toPath(), timestamp)
        assertNotEquals(initial, arguments(classpath = listOf(first, second)))
    }

    @Test
    fun `changed contents with the same size and timestamp retain the cache key`() {
        val jar = root.resolve("library.jar").toFile().apply { writeText("first") }
        val timestamp = Files.getLastModifiedTime(jar.toPath())
        val initial = arguments(classpath = listOf(jar))
        jar.writeText("other")
        Files.setLastModifiedTime(jar.toPath(), timestamp)
        assertEquals(initial, arguments(classpath = listOf(jar)))
    }

    @Test
    fun `changed jar timestamps invalidate the cache even with identical contents`() {
        val jar = root.resolve("library.jar").toFile().apply { writeText("unchanged") }
        val initial = arguments(classpath = listOf(jar))
        Files.setLastModifiedTime(jar.toPath(), FileTime.fromMillis(1))
        assertNotEquals(initial, arguments(classpath = listOf(jar)))
    }

    @Test
    fun `changed class file metadata in directory classpaths invalidates the cache`() {
        val classes = root.resolve("classes").toFile().apply { mkdirs() }
        val file = File(classes, "Example.class").apply { writeText("old") }
        val initial = arguments(classpath = listOf(classes))
        file.writeText("new class contents")
        assertNotEquals(initial, arguments(classpath = listOf(classes)))
    }

    @Test
    fun `unusable cache directory reports an IO failure`() {
        val file = root.resolve("not-a-directory").toFile().apply { writeText("file") }
        assertFailsWith<IOException> { arguments(directory = file) }
    }

    private fun arguments(
        directory: File = root.resolve("cache").toFile(),
        classpath: List<File> = emptyList(),
        version: Int = 25,
        identity: List<String> = listOf("25", "vendor", "/jdk"),
        jvmArgs: List<String> = emptyList(),
    ): List<String> = AnalyzerAotCache.arguments(directory, classpath, version, identity, jvmArgs)
}
