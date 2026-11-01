package io.github.ninebolt6.klasstree.analyzer

import com.intellij.openapi.Disposable
import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneWorkaroundApi
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.projectStructure.StandaloneLibraryScopeConstructionMode
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import java.nio.file.Files
import java.nio.file.Path

@OptIn(KaExperimentalApi::class, StandaloneWorkaroundApi::class)
internal fun buildAnalysisSession(
    spec: AnalysisSpec,
    projectDisposable: Disposable,
): StandaloneAnalysisAPISession = buildStandaloneAnalysisAPISession(projectDisposable = projectDisposable) {
    // KT-70489: FIR/PSI caches must be dropped during long analyses on monolith inputs.
    enableCacheCleaner()
    buildKtModuleProvider {
        platform = JvmPlatforms.defaultJvmPlatform
        // KT-71706: the default scope enumerates every library class file and balloons heap.
        val scopeMode = StandaloneLibraryScopeConstructionMode.Trie
        val sdk = buildKtSdkModule {
            platform = JvmPlatforms.defaultJvmPlatform
            addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
            libraryScopeConstructionMode = scopeMode
            libraryName = "JDK"
        }

        val specsById = spec.modules.associateBy { it.id }
        val builtModules = LinkedHashMap<String, KaSourceModule>()
        // One library module per normalized classpath entry is shared across source modules.
        // This indexes each jar once even when module classpaths differ slightly.
        val builtLibraries = LinkedHashMap<Path, KaLibraryModule>()
        // A source root claimed by several modules would bind its files to more than
        // one KaModule and corrupt resolution, so the first module claims it.
        val claimedSourceRoots = HashSet<Path>()
        val building = LinkedHashSet<String>()

        fun libraryFor(path: Path): KaLibraryModule =
            builtLibraries.getOrPut(path) {
                buildKtLibraryModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    libraryName = "lib:${builtLibraries.size}"
                    libraryScopeConstructionMode = scopeMode
                    addBinaryRoots(listOf(path))
                }
            }

        fun buildSourceModule(id: String): KaSourceModule {
            builtModules[id]?.let { return it }
            val moduleSpec = specsById[id]
                ?: error("Unknown analysis module '$id'. Known modules: ${specsById.keys.sorted()}")
            check(building.add(id)) {
                "Cyclic analysis module dependency: ${building.joinToString(" -> ")} -> $id"
            }
            try {
                val libraryPaths = moduleSpec.classpath
                    .map { Path.of(it).toAbsolutePath().normalize() }
                    .filter { Files.exists(it) }
                    .distinct()

                val module = buildKtSourceModule {
                    platform = JvmPlatforms.defaultJvmPlatform
                    moduleName = moduleSpec.moduleName
                    addSourceRoots(
                        moduleSpec.sourceRoots
                            .map { Path.of(it).toAbsolutePath().normalize() }
                            .filter { Files.exists(it) }
                            .filter { claimedSourceRoots.add(it) },
                    )
                    addRegularDependency(sdk)
                    libraryPaths.forEach { addRegularDependency(libraryFor(it)) }
                    moduleSpec.dependencies.forEach { dependencyId ->
                        val dependencySpec = specsById[dependencyId] ?: return@forEach
                        val dependency = buildSourceModule(dependencyId)
                        addRegularDependency(dependency)
                        // Source sets of one project see each other's internal
                        // declarations, like Kotlin's friend paths for test→main.
                        if (dependencySpec.projectPath == moduleSpec.projectPath) {
                            addFriendDependency(dependency)
                        }
                    }
                }
                builtModules[id] = module
                return module
            } finally {
                building.remove(id)
            }
        }

        spec.modules.forEach { moduleSpec ->
            addModule(buildSourceModule(moduleSpec.id))
        }
    }
}
