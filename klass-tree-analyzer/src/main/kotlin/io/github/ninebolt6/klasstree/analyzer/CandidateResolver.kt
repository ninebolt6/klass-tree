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
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolLocation
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.allOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.symbols.directlyOverriddenSymbols
import org.jetbrains.kotlin.analysis.api.symbols.fakeOverrideOriginal
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.resolution.KtResolvable
import org.jetbrains.kotlin.resolution.KtResolvableCall

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
            is CallCandidate -> resolveCallCandidate(candidate.element)
            is ConstructorCallCandidate -> resolveCallCandidate(candidate.element)
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
    private fun resolveCallCandidate(element: KtResolvableCall) {
        handleCalls(element.resolveSuccessfulCall(), element) { symbol ->
            when (symbol) {
                is KaConstructorSymbol -> EdgeKind.CONSTRUCTOR_CALL
                is KaSamConstructorSymbol -> EdgeKind.CONSTRUCTOR_CALL
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

            is KaEnumEntrySymbol -> {
                handleSymbol(
                    symbol,
                    candidate.element,
                    EdgeKind.PROPERTY_ACCESS,
                )
            }

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

    context(session: KaSession)
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

    context(session: KaSession)
    private fun handleSymbol(
        symbol: KaSymbol?,
        element: PsiElement,
        kind: EdgeKind,
    ) {
        if (symbol == null) {
            stats.unresolved++
            return
        }
        val originalSymbol = symbol.unwrapSubstitutionOverrides()
        when (dispositionOf(originalSymbol)) {
            SymbolDisposition.SKIPPED -> {
                stats.skipped++
            }

            SymbolDisposition.EXTERNAL -> {
                stats.resolvedExternal++
            }

            SymbolDisposition.RECORD -> {
                val to = index.nodeForSymbol(originalSymbol)
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

            KaSymbolOrigin.SAM_CONSTRUCTOR -> {
                SymbolDisposition.RECORD
            }

            KaSymbolOrigin.SOURCE_MEMBER_GENERATED -> {
                // Generated enum members (valueOf, values, entries) are real API surfaces of the
                // enum; other generated members (data class copy/componentN, …) are not.
                if (symbol is KaConstructorSymbol || (symbol.psi as? KtClass)?.isEnum() == true) {
                    SymbolDisposition.RECORD
                } else {
                    SymbolDisposition.SKIPPED
                }
            }

            KaSymbolOrigin.LIBRARY, KaSymbolOrigin.JAVA_LIBRARY -> {
                SymbolDisposition.EXTERNAL
            }

            else -> {
                SymbolDisposition.SKIPPED
            }
        }

    private enum class SymbolDisposition { RECORD, SKIPPED, EXTERNAL }
}

@OptIn(KaExperimentalApi::class)
context(session: KaSession)
private fun KaSymbol.unwrapSubstitutionOverrides(): KaSymbol =
    generateSequence(this) { symbol ->
        if (symbol !is KaCallableSymbol || symbol.origin != KaSymbolOrigin.SUBSTITUTION_OVERRIDE) {
            return@generateSequence null
        }
        val parent =
            if (symbol is KaJavaFieldSymbol) {
                symbol.fakeOverrideOriginal
            } else {
                symbol.directlyOverriddenSymbols.singleOrNull()
            }
        parent?.takeIf { it.coversOverridesOf(symbol) }
    }.last()

/** Covers every overridden declaration so unwrapping preserves inherited parents, e.g. substituted intersections. */
context(session: KaSession)
private fun KaCallableSymbol.coversOverridesOf(other: KaCallableSymbol): Boolean {
    val covered = allOverriddenSymbols.toSet() + this
    return other.allOverriddenSymbols.all { it in covered }
}
