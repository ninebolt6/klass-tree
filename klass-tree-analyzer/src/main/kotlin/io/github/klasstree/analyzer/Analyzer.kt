package io.github.klasstree.analyzer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import io.github.klasstree.graph.AnalysisSpec
import io.github.klasstree.graph.DependencyGraph
import io.github.klasstree.graph.EdgeKind
import io.github.klasstree.graph.GraphBuilder
import io.github.klasstree.graph.ResolutionStats
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.resolution.calls
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulCall
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulSymbol
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Runs Kotlin Analysis API Standalone over the modules described by [AnalysisSpec] and returns a
 * platform-independent dependency graph.
 *
 * This class is intentionally only referenced from the analyzer's own `main` (which the Gradle
 * plugin launches in a separate JVM). The Gradle daemon never loads Analysis API classes.
 */
@OptIn(KaExperimentalApi::class)
class Analyzer {
    fun analyze(spec: AnalysisSpec, disposeGlobalServices: Boolean = false): DependencyGraph {
        require(spec.modules.isNotEmpty()) {
            "The analysis spec contains no modules. Nothing can be analyzed."
        }
        val startedAt = System.nanoTime()
        val disposable = Disposer.newDisposable("klass-tree-analysis")
        try {
            val session = buildStandaloneAnalysisAPISession(projectDisposable = disposable) {
                buildKtModuleProvider {
                    platform = JvmPlatforms.defaultJvmPlatform
                    val sdk = buildKtSdkModule {
                        platform = JvmPlatforms.defaultJvmPlatform
                        addBinaryRootsFromJdkHome(Path.of(System.getProperty("java.home")), isJre = false)
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

            // Enumerating PSI files, indexing declarations, collecting candidates, and resolving
            // them all happen under one read action. Besides satisfying the platform's read-access
            // requirement, this avoids acquiring a read action once per file, which is measurable
            // on modules with thousands of files.
            return ApplicationManager.getApplication().runReadAction(
                Computable<DependencyGraph> {
                    val fileInfos = collectFileInfos(session, spec)
                    val builder = GraphBuilder()
                    val index = DeclarationIndex(builder)
                    val stats = MutableResolutionStats()

                    fileInfos.forEach { info ->
                        try {
                            index.indexFile(info)
                        } catch (throwable: Throwable) {
                            if (!recordFileError(info, throwable, stats)) throw throwable
                        }
                    }
                    fileInfos.forEach { info -> analyzeFile(info, index, stats) }

                    val resolution = stats.toImmutable()
                    if (spec.failOnAnalysisError && resolution.analysisErrors.isNotEmpty()) {
                        throw IllegalStateException(
                            "klass-tree: ${resolution.analysisErrors.size} analysis error(s):\n" +
                                resolution.analysisErrors.joinToString("\n") { "  - $it" },
                        )
                    }

                    builder.build(
                        generatedAt = Instant.now().toString(),
                        projectRoot = spec.projectRoot,
                        elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                        resolution = resolution,
                        sourceFileCount = fileInfos.size,
                    )
                },
            )
        } finally {
            Disposer.dispose(disposable)
            if (disposeGlobalServices) {
                disposeGlobalStandaloneApplicationServices()
            }
        }
    }

    private fun collectFileInfos(
        session: StandaloneAnalysisAPISession,
        spec: AnalysisSpec,
    ): List<SourceFileInfo> {
        val filesByModuleName = session.modulesWithFiles.mapKeys { (module, _) -> module.name }
        val projectRoot = Path.of(spec.projectRoot).toAbsolutePath().normalize()
        val result = mutableListOf<SourceFileInfo>()
        val claimed = HashSet<Path>()

        for (moduleSpec in spec.modules) {
            val psiFiles = filesByModuleName[moduleSpec.moduleName] ?: continue
            for (psiFile in psiFiles) {
                val file = psiFile as? KtFile ?: continue
                if (file.isScript()) continue
                val absolutePath = file.virtualFile?.path?.let { Path.of(it).toAbsolutePath().normalize() } ?: continue
                // Overlapping (non-identical) source roots can still list the same file.
                if (!claimed.add(absolutePath)) continue
                result += SourceFileInfo(
                    module = moduleSpec,
                    file = file,
                    absolutePath = absolutePath,
                    relativePath = relativize(projectRoot, absolutePath),
                )
            }
        }
        return result.sortedWith(compareBy({ it.module.id }, { it.relativePath }))
    }

    private fun analyzeFile(
        info: SourceFileInfo,
        index: DeclarationIndex,
        stats: MutableResolutionStats,
    ) {
        try {
            val candidates = ReferenceCollector.collect(info.file)
            if (candidates.isEmpty()) return
            analyze(info.file) {
                for (candidate in candidates) {
                    try {
                        resolveCandidate(candidate, index, stats)
                    } catch (exception: Exception) {
                        stats.analysisErrors += buildString {
                            append(info.relativePath)
                            index.callSite(candidate.element)?.let { site ->
                                append(':')
                                append(site.line)
                                append(':')
                                append(site.column)
                            }
                            append(": ")
                            append(exception::class.simpleName)
                            append(": ")
                            append(exception.message)
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (!recordFileError(info, throwable, stats)) throw throwable
        }
    }

    // Whole-file isolation covers ordinary exceptions plus StackOverflowErrors from deeply
    // recursive PSI or FIR structures; other Errors such as OOM still abort the analysis.
    private fun recordFileError(
        info: SourceFileInfo,
        throwable: Throwable,
        stats: MutableResolutionStats,
    ): Boolean {
        if (throwable !is Exception && throwable !is StackOverflowError) return false
        stats.analysisErrors += buildString {
            append(info.relativePath)
            append(": ")
            append(throwable::class.simpleName)
            append(": ")
            append(throwable.message)
        }
        return true
    }

    context(session: KaSession)
    private fun resolveCandidate(
        candidate: ReferenceCandidate,
        index: DeclarationIndex,
        stats: MutableResolutionStats,
    ) {
        stats.candidates++
        when (candidate) {
            is TypeReferenceCandidate -> {
                handleSymbol(
                    symbol = candidate.element.resolveSuccessfulSymbol(),
                    element = candidate.element,
                    kind = EdgeKind.TYPE_REFERENCE,
                    index = index,
                    stats = stats,
                )
            }

            is InheritanceCandidate -> {
                handleSymbol(
                    symbol = candidate.element.resolveSuccessfulSymbol(),
                    element = candidate.element,
                    kind = EdgeKind.INHERITANCE,
                    index = index,
                    stats = stats,
                )
            }

            is CallCandidate -> {
                val call = candidate.element.resolveSuccessfulCall()
                if (call == null) {
                    stats.unresolved++
                    return
                }
                for (simpleCall in call.calls) {
                    val symbol = simpleCall.signature.symbol
                    val kind = when (symbol) {
                        is KaConstructorSymbol -> EdgeKind.CONSTRUCTOR_CALL
                        is KaNamedFunctionSymbol -> EdgeKind.FUNCTION_CALL
                        is KaPropertySymbol -> EdgeKind.PROPERTY_ACCESS
                        else -> null
                    }
                    if (kind == null) {
                        stats.skipped++
                    } else {
                        handleSymbol(symbol, candidate.element, kind, index, stats)
                    }
                }
            }

            is AnnotationCandidate -> {
                handleSymbol(
                    symbol = candidate.element.resolveSuccessfulSymbol(),
                    element = candidate.element,
                    kind = EdgeKind.ANNOTATION,
                    index = index,
                    stats = stats,
                )
            }

            is PropertyCandidate -> {
                val resolvable = candidate.element as? org.jetbrains.kotlin.resolution.KtResolvable
                if (resolvable == null) {
                    stats.skipped++
                    return
                }
                when (val symbol = resolvable.resolveSuccessfulSymbol()) {
                    is KaPropertySymbol -> handleSymbol(
                        symbol, candidate.element, EdgeKind.PROPERTY_ACCESS, index, stats,
                    )

                    // A function used as a call target is represented by the call resolution
                    // (FUNCTION_CALL / CONSTRUCTOR_CALL). `::foo` is handled separately.
                    is KaNamedFunctionSymbol -> stats.skipped++

                    is KaClassLikeSymbol -> handleSymbol(
                        symbol, candidate.element, EdgeKind.TYPE_REFERENCE, index, stats,
                    )

                    else -> stats.skipped++
                }
            }

            is CallableReferenceCandidate -> {
                val call = candidate.element.resolveSuccessfulCall()
                if (call == null) {
                    stats.unresolved++
                    return
                }
                for (simpleCall in call.calls) {
                    handleSymbol(
                        symbol = simpleCall.signature.symbol,
                        element = candidate.element,
                        kind = EdgeKind.CALLABLE_REFERENCE,
                        index = index,
                        stats = stats,
                    )
                }
            }

            is OperatorCandidate -> {
                val call = candidate.element.resolveSuccessfulCall()
                if (call == null) {
                    stats.unresolved++
                    return
                }
                for (simpleCall in call.calls) {
                    handleSymbol(
                        symbol = simpleCall.signature.symbol,
                        element = candidate.element,
                        kind = EdgeKind.OPERATOR_OR_SYNTHETIC_CALL,
                        index = index,
                        stats = stats,
                    )
                }
            }
        }
    }

    private fun handleSymbol(
        symbol: KaSymbol?,
        element: com.intellij.psi.PsiElement,
        kind: EdgeKind,
        index: DeclarationIndex,
        stats: MutableResolutionStats,
    ) {
        if (symbol == null) {
            stats.unresolved++
            return
        }
        if (symbol is KaPackageSymbol || symbol is KaTypeParameterSymbol) {
            stats.skipped++
            return
        }
        if (symbol.location == KaSymbolLocation.LOCAL) {
            stats.skipped++
            return
        }

        when (symbol.origin) {
            KaSymbolOrigin.SOURCE -> Unit

            // Implicit primary constructors and annotation constructors are generated members of
            // source classes. They are needed for CONSTRUCTOR_CALL / ANNOTATION edges. Other
            // generated members (copy, componentN, ...) stay out of the initial graph.
            KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> {
                if (symbol !is KaConstructorSymbol) {
                    stats.skipped++
                    return
                }
            }

            KaSymbolOrigin.LIBRARY, KaSymbolOrigin.JAVA_LIBRARY -> {
                stats.resolvedExternal++
                return
            }

            // Java sources, compiler-generated members and compiler plugin declarations are not
            // part of the initial graph. They are counted separately from unresolved references.
            else -> {
                stats.skipped++
                return
            }
        }

        val to = index.nodeForSymbol(symbol)
        if (to == null) {
            stats.skipped++
            return
        }
        val from = index.nodeForElement(element)
        if (from == null) {
            stats.skipped++
            return
        }
        stats.resolvedSource++
        if (from.id == to.id) {
            stats.selfEdgesSkipped++
            return
        }
        val callSite = index.callSite(element)
        if (callSite == null) {
            stats.skipped++
            return
        }
        index.addEdge(from, to, kind, callSite)
    }

    private fun relativize(root: Path, path: Path): String {
        val relative = try {
            root.relativize(path).toString()
        } catch (_: IllegalArgumentException) {
            path.toString()
        }
        val normalized = relative.replace('\\', '/')
        return if (normalized.startsWith("../")) path.toString().replace('\\', '/') else normalized
    }
}

private class MutableResolutionStats {
    var candidates: Int = 0
    var resolvedSource: Int = 0
    var resolvedExternal: Int = 0
    var unresolved: Int = 0
    var skipped: Int = 0
    var selfEdgesSkipped: Int = 0
    val analysisErrors = mutableListOf<String>()

    fun toImmutable(): ResolutionStats = ResolutionStats(
        candidates = candidates,
        resolvedSource = resolvedSource,
        resolvedExternal = resolvedExternal,
        unresolved = unresolved,
        skipped = skipped,
        selfEdgesSkipped = selfEdgesSkipped,
        analysisErrors = analysisErrors.toList(),
    )
}
