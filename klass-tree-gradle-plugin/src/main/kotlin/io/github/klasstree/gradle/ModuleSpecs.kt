package io.github.klasstree.gradle

import io.github.klasstree.graph.ModuleSpec

internal object ModuleSpecs {
    fun build(candidates: List<AnalysisModuleCandidate>): List<ModuleSpec> {
        val sortedCandidates = candidates.sortedBy { it.moduleId }
        val includedIds = sortedCandidates.mapTo(LinkedHashSet()) { it.moduleId }
        val moduleNameCounts = mutableMapOf<String, Int>()

        return sortedCandidates.map { candidate ->
            val dependencyIds = LinkedHashSet<String>()
            dependencyIds += candidate.associatedModuleIds
            candidate.projectDependencyPaths.mapTo(dependencyIds) { moduleId(it, "main") }
            if (candidate.sourceSetName != "main") {
                dependencyIds += moduleId(candidate.projectPath, "main")
            }

            val count = moduleNameCounts.merge(candidate.moduleName, 1, Int::plus) ?: 1
            val uniqueModuleName = if (count == 1) candidate.moduleName else "${candidate.moduleName}_$count"

            ModuleSpec(
                id = candidate.moduleId,
                projectPath = candidate.projectPath,
                sourceSetName = candidate.sourceSetName,
                moduleName = uniqueModuleName,
                sourceRoots = candidate.sourceRoots,
                classpath = candidate.classpath,
                dependencies = dependencyIds
                    .filter { it != candidate.moduleId && it in includedIds }
                    .sorted(),
            )
        }
    }

    private fun moduleId(projectPath: String, sourceSetName: String): String =
        (if (projectPath == ":") ":" else "$projectPath:") + sourceSetName
}
