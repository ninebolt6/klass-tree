package io.github.ninebolt6.klasstree.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class KlassTreePluginTest {
    @Test
    fun `registers the extension and aggregate task on the root project`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(KlassTreePlugin::class.java)

        val extension = project.extensions.findByType(KlassTreeExtension::class.java)
        assertNotNull(extension)
        assertEquals(false, extension.includeTests.get())
        assertEquals(setOf("json", "dot"), extension.formats.get())
        assertEquals(
            KlassTreeExtension.DEFAULT_ANALYSIS_API_VERSION,
            extension.analysisApiVersion.get(),
        )
        assertNotNull(project.tasks.findByName("klassTree"))
    }

    @Test
    fun `rejects application to a non-root project`() {
        val root = ProjectBuilder.builder().build()
        val child =
            ProjectBuilder
                .builder()
                .withParent(root)
                .withName("child")
                .build()

        assertFailsWith<GradleException> {
            child.pluginManager.apply(KlassTreePlugin::class.java)
        }
    }
}
