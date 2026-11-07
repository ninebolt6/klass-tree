package io.github.ninebolt6.klasstree.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleSpecsTest {
    @Test
    fun `module name suffixes are deterministic regardless of candidate order`() {
        val candidates =
            listOf(
                candidate(":z:main", ":z", "shared"),
                candidate(":a:main", ":a", "shared"),
            )

        val forward = ModuleSpecs.build(candidates).associate { it.id to it.moduleName }
        val reversed = ModuleSpecs.build(candidates.reversed()).associate { it.id to it.moduleName }

        assertEquals(
            mapOf(":a:main" to "shared", ":z:main" to "shared_2"),
            forward,
        )
        assertEquals(forward, reversed)
    }

    @Test
    fun `self dependencies and unknown dependencies are dropped`() {
        val modules =
            ModuleSpecs.build(
                listOf(
                    candidate(
                        moduleId = ":app:main",
                        projectPath = ":app",
                        moduleName = "app",
                        projectDependencyPaths = listOf(":app", ":missing"),
                        associatedModuleIds = listOf(":app:main", ":missing:main"),
                    ),
                ),
            )

        assertEquals(emptyList(), modules.single().dependencies)
    }

    @Test
    fun `non-main source sets depend on main and output is sorted by module id`() {
        val modules =
            ModuleSpecs.build(
                listOf(
                    candidate(":z:main", ":z", "z"),
                    candidate(":app:test", ":app", "app_test", "test"),
                    candidate(":app:main", ":app", "app_main"),
                ),
            )

        assertEquals(listOf(":app:main", ":app:test", ":z:main"), modules.map { it.id })
        assertEquals(listOf(":app:main"), modules.single { it.id == ":app:test" }.dependencies)
    }

    @Test
    fun `project dependencies map to main modules including the root project`() {
        val modules =
            ModuleSpecs.build(
                listOf(
                    candidate(
                        moduleId = ":api:main",
                        projectPath = ":api",
                        moduleName = "api_main",
                        projectDependencyPaths = listOf(":shared"),
                    ),
                    candidate(
                        moduleId = ":app:main",
                        projectPath = ":app",
                        moduleName = "app_main",
                        projectDependencyPaths = listOf(":"),
                    ),
                    candidate(":shared:main", ":shared", "shared_main"),
                    candidate(":main", ":", "root_main"),
                ),
            )

        assertEquals(listOf(":shared:main"), modules.single { it.id == ":api:main" }.dependencies)
        assertEquals(listOf(":main"), modules.single { it.id == ":app:main" }.dependencies)
    }

    private fun candidate(
        moduleId: String,
        projectPath: String,
        moduleName: String,
        sourceSetName: String = "main",
        projectDependencyPaths: List<String> = emptyList(),
        associatedModuleIds: List<String> = emptyList(),
    ) = AnalysisModuleCandidate(
        projectPath = projectPath,
        sourceSetName = sourceSetName,
        moduleId = moduleId,
        moduleName = moduleName,
        sourceRoots = emptyList(),
        classpath = emptyList(),
        projectDependencyPaths = projectDependencyPaths,
        associatedModuleIds = associatedModuleIds,
    )
}
