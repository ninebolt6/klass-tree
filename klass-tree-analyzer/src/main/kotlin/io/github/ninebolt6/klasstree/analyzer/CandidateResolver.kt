package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.PsiElement
import io.github.ninebolt6.klasstree.graph.EdgeKind
import io.github.ninebolt6.klasstree.graph.GraphNode
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleOrMultiCall
import org.jetbrains.kotlin.analysis.api.resolution.calls
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulCall
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.resolution.KtResolvable

/**
 * Resolves collected [ReferenceCandidate]s into graph edges. [JavaCandidate]s are delegated to
 * [JavaCandidateResolver]. Must be called inside an `analyze` block.
 */
@OptIn(KaExperimentalApi::class)
internal class CandidateResolver(
    internal val index: DeclarationIndex,
    internal val stats: MutableResolutionStats,
) {
    private val edges = EdgeRecorder(index, stats)
    private val javaResolver = JavaCandidateResolver(index, stats)

    context(session: KaSession)
    fun resolve(candidate: ReferenceCandidate) {
        stats.candidates++
        when (candidate) {
            is KotlinSymbolCandidate -> {
                handleSymbol(
                    symbol = candidate.element.resolveSuccessfulSymbol(),
                    element = candidate.element,
                    kind = candidate.kind,
                )
            }

            is CallCandidate -> {
                handleCalls(candidate.element.resolveSuccessfulCall(), candidate.element) { symbol ->
                    when (symbol) {
                        is KaConstructorSymbol -> EdgeKind.CONSTRUCTOR_CALL
                        is KaNamedFunctionSymbol -> EdgeKind.FUNCTION_CALL
                        is KaPropertySymbol -> EdgeKind.PROPERTY_ACCESS
                        else -> null
                    }
                }
            }

            is AnnotationCandidate -> {
                handleSymbol(
                    symbol = candidate.element.resolveSuccessfulSymbol(),
                    element = candidate.element,
                    kind = EdgeKind.ANNOTATION,
                )
            }

            is PropertyCandidate -> {
                val resolvable = candidate.element as? KtResolvable
                if (resolvable == null) {
                    stats.skipped++
                    return
                }
                when (val symbol = resolvable.resolveSuccessfulSymbol()) {
                    is KaPropertySymbol -> handleSymbol(
                        symbol, candidate.element, EdgeKind.PROPERTY_ACCESS,
                    )

                    is KaJavaFieldSymbol -> handleSymbol(
                        symbol, candidate.element, EdgeKind.PROPERTY_ACCESS,
                    )

                    // A function used as a call target is represented by the call resolution
                    // (FUNCTION_CALL / CONSTRUCTOR_CALL). `::foo` is handled separately.
                    is KaNamedFunctionSymbol -> stats.skipped++

                    is KaClassLikeSymbol -> handleSymbol(
                        symbol, candidate.element, EdgeKind.TYPE_REFERENCE,
                    )

                    else -> stats.skipped++
                }
            }

            is CallableReferenceCandidate -> {
                handleCalls(candidate.element.resolveSuccessfulCall(), candidate.element) {
                    EdgeKind.CALLABLE_REFERENCE
                }
            }

            is OperatorCandidate -> {
                handleCalls(candidate.element.resolveSuccessfulCall(), candidate.element) {
                    EdgeKind.OPERATOR_OR_SYNTHETIC_CALL
                }
            }

            is JavaCandidate -> javaResolver.resolve(candidate)
        }
    }

    private fun handleCalls(
        call: KaSimpleOrMultiCall?,
        element: PsiElement,
        kindFor: (KaSymbol) -> EdgeKind?,
    ) {
        if (call == null) {
            stats.unresolved++
            return
        }
        for (simpleCall in call.calls) {
            val symbol = simpleCall.signature.symbol
            val kind = kindFor(symbol)
            if (kind == null) {
                stats.skipped++
            } else {
                handleSymbol(symbol, element, kind)
            }
        }
    }

    private fun handleSymbol(
        symbol: KaSymbol?,
        element: PsiElement,
        kind: EdgeKind,
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
            KaSymbolOrigin.SOURCE, KaSymbolOrigin.JAVA_SOURCE -> Unit

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
        edges.record(to, element, kind)
    }
}
