package io.github.ninebolt6.klasstree.gradle

import io.github.ninebolt6.klasstree.graph.DeniedDependency
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlassTreePluginTest {
    @Test
    fun `deny accumulates target-only and source-target rules`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(KlassTreePlugin::class.java)

        val extension = project.extensions.getByType(KlassTreeExtension::class.java)
        extension.deny(to = "legacy")
        extension.deny(from = "domain", to = "infra")
        assertEquals(
            listOf(DeniedDependency(to = "legacy"), DeniedDependency(from = "domain", to = "infra")),
            extension.deniedDependencies.get(),
        )
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
