package io.github.ninebolt6.klasstree.analyzer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.ninebolt6.klasstree.graph.AnalysisSpec
import io.github.ninebolt6.klasstree.graph.GraphBuilder
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.time.Instant

/**
 * This class is intentionally only referenced from the analyzer's own `main` (which the Gradle
 * plugin launches in a separate JVM). The Gradle daemon never loads Analysis API classes.
 */
@OptIn(KaExperimentalApi::class)
class Analyzer {
    fun analyze(spec: AnalysisSpec, disposeGlobalServices: Boolean = false): AnalysisResult {
        require(spec.modules.isNotEmpty()) {
            "The analysis spec contains no modules. Nothing can be analyzed."
        }
        val startedAt = System.nanoTime()
        val disposable = Disposer.newDisposable("klass-tree-analysis")
        try {
            val session = buildAnalysisSession(spec, disposable)

            // Enumerating PSI files, indexing declarations, collecting candidates, and resolving
            // them all happen under one read action. Besides satisfying the platform's read-access
            // requirement, this avoids acquiring a read action once per file, which is measurable
            // on modules with thousands of files.
            return ApplicationManager.getApplication().runReadAction(
                Computable<AnalysisResult> {
                    val fileInfos = collectFileInfos(session, spec)
                    val builder = GraphBuilder()
                    val index = DeclarationIndex(builder)
                    val stats = MutableResolutionStats()
                    val resolver = CandidateResolver(index, stats)

                    fileInfos.forEach { info ->
                        try {
                            index.indexFile(info)
                        } catch (throwable: Throwable) {
                            if (!recordFileError(info, throwable, stats)) throw throwable
                        }
                    }
                    fileInfos.forEach { info -> analyzeFile(info, resolver) }

                    val resolution = stats.toImmutable()
                    if (spec.failOnAnalysisError && resolution.analysisErrors.isNotEmpty()) {
                        throw IllegalStateException(
                            "klass-tree: ${resolution.analysisErrors.size} analysis error(s):\n" +
                                resolution.analysisErrors.joinToString("\n") { "  - $it" },
                        )
                    }

                    val graph = builder.build(
                        generatedAt = Instant.now().toString(),
                        projectRoot = spec.projectRoot,
                    )
                    AnalysisResult(
                        graph = graph,
                        stats = GraphStats(
                            nodeCount = graph.nodes.size,
                            edgeCount = graph.edges.size,
                            callSiteCount = graph.edges.sumOf { it.callSites.size },
                            sourceFileCount = fileInfos.size,
                            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
                            resolution = resolution,
                        ),
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
                val file = when {
                    psiFile is KtFile -> {
                        if (psiFile.isScript()) continue
                        psiFile
                    }

                    psiFile is PsiJavaFile && spec.includeJavaSources -> psiFile
                    else -> continue
                }
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
        resolver: CandidateResolver,
    ) {
        val stats = resolver.stats
        try {
            // A syntactically broken file still yields a PSI tree; flag it instead of silently
            // dropping whatever did not parse.
            if (PsiTreeUtil.findChildOfType(info.file, PsiErrorElement::class.java) != null) {
                stats.analysisErrors += "${info.relativePath}: PSI parse errors"
            }
            val candidates = when (val file = info.file) {
                is KtFile -> ReferenceCollector.collect(file)
                is PsiJavaFile -> JavaReferenceCollector.collect(file)
                else -> emptyList()
            }
            if (candidates.isEmpty()) return
            analyze(info.file) {
                for (candidate in candidates) {
                    try {
                        resolver.resolve(candidate)
                    } catch (exception: Exception) {
                        stats.analysisErrors += buildString {
                            append(info.relativePath)
                            resolver.index.callSite(candidate.element)?.let { site ->
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
