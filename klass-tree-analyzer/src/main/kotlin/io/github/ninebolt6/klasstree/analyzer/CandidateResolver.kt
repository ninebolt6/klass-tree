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
            is KotlinSymbolCandidate -> resolveSymbolCandidate(candidate)
            is CallCandidate -> resolveCallCandidate(candidate)
            is AnnotationCandidate -> resolveAnnotationCandidate(candidate)
            is PropertyCandidate -> resolvePropertyCandidate(candidate)
            is CallableReferenceCandidate -> resolveCallableReferenceCandidate(candidate)
            is OperatorCandidate -> resolveOperatorCandidate(candidate)
            is JavaCandidate -> javaResolver.resolve(candidate)
        }
    }

    context(session: KaSession)
    private fun resolveSymbolCandidate(candidate: KotlinSymbolCandidate) {
        handleSymbol(
            symbol = candidate.element.resolveSuccessfulSymbol(),
            element = candidate.element,
            kind = candidate.kind,
        )
    }

    context(session: KaSession)
    private fun resolveCallCandidate(candidate: CallCandidate) {
        handleCalls(candidate.element.resolveSuccessfulCall(), candidate.element) { symbol ->
            when (symbol) {
                is KaConstructorSymbol -> EdgeKind.CONSTRUCTOR_CALL
                is KaNamedFunctionSymbol -> EdgeKind.FUNCTION_CALL
                is KaPropertySymbol -> EdgeKind.PROPERTY_ACCESS
                else -> null
            }
        }
    }

    context(session: KaSession)
    private fun resolveAnnotationCandidate(candidate: AnnotationCandidate) {
        handleSymbol(
            symbol = candidate.element.resolveSuccessfulSymbol(),
            element = candidate.element,
            kind = EdgeKind.ANNOTATION,
        )
    }

    context(session: KaSession)
    private fun resolvePropertyCandidate(candidate: PropertyCandidate) {
        val resolvable = candidate.element as? KtResolvable
        if (resolvable == null) {
            stats.skipped++
            return
        }
        when (val symbol = resolvable.resolveSuccessfulSymbol()) {
            is KaPropertySymbol -> {
                handleSymbol(
                    symbol,
                    candidate.element,
                    EdgeKind.PROPERTY_ACCESS,
                )
            }

            is KaJavaFieldSymbol -> {
                handleSymbol(
                    symbol,
                    candidate.element,
                    EdgeKind.PROPERTY_ACCESS,
                )
            }

            // A function used as a call target is represented by the call resolution
            // (FUNCTION_CALL / CONSTRUCTOR_CALL). `::foo` is handled separately.
            is KaNamedFunctionSymbol -> {
                stats.skipped++
            }

            is KaClassLikeSymbol -> {
                handleSymbol(
                    symbol,
                    candidate.element,
                    EdgeKind.TYPE_REFERENCE,
                )
            }

            else -> {
                stats.skipped++
            }
        }
    }

    context(session: KaSession)
    private fun resolveCallableReferenceCandidate(candidate: CallableReferenceCandidate) {
        handleCalls(candidate.element.resolveSuccessfulCall(), candidate.element) {
            EdgeKind.CALLABLE_REFERENCE
        }
    }

    context(session: KaSession)
    private fun resolveOperatorCandidate(candidate: OperatorCandidate) {
        handleCalls(candidate.element.resolveSuccessfulCall(), candidate.element) {
            EdgeKind.OPERATOR_OR_SYNTHETIC_CALL
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
        when (dispositionOf(symbol)) {
            SymbolDisposition.SKIPPED -> {
                stats.skipped++
            }

            SymbolDisposition.EXTERNAL -> {
                stats.resolvedExternal++
            }

            SymbolDisposition.RECORD -> {
                val to = index.nodeForSymbol(symbol)
                if (to == null) {
                    stats.skipped++
                } else {
                    edges.record(to, element, kind)
                }
            }
        }
    }

    private fun dispositionOf(symbol: KaSymbol): SymbolDisposition =
        when {
            symbol is KaPackageSymbol || symbol is KaTypeParameterSymbol -> SymbolDisposition.SKIPPED
            symbol.location == KaSymbolLocation.LOCAL -> SymbolDisposition.SKIPPED
            else -> dispositionForOrigin(symbol)
        }

    private fun dispositionForOrigin(symbol: KaSymbol): SymbolDisposition =
        when (symbol.origin) {
            KaSymbolOrigin.SOURCE, KaSymbolOrigin.JAVA_SOURCE -> {
                SymbolDisposition.RECORD
            }

            // Implicit primary constructors and annotation constructors are generated members of
            // source classes. They are needed for CONSTRUCTOR_CALL / ANNOTATION edges. Other
            // generated members (copy, componentN, ...) stay out of the initial graph.
            KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> {
                if (symbol is KaConstructorSymbol) {
                    SymbolDisposition.RECORD
                } else {
                    SymbolDisposition.SKIPPED
                }
            }

            KaSymbolOrigin.LIBRARY, KaSymbolOrigin.JAVA_LIBRARY -> {
                SymbolDisposition.EXTERNAL
            }

            // Java sources, compiler-generated members and compiler plugin declarations are not
            // part of the initial graph. They are counted separately from unresolved references.
            else -> {
                SymbolDisposition.SKIPPED
            }
        }

    private enum class SymbolDisposition { RECORD, SKIPPED, EXTERNAL }
}
