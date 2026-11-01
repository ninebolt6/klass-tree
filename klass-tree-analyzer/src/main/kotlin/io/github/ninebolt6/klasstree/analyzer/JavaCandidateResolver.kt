package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import io.github.ninebolt6.klasstree.graph.EdgeKind

/**
 * Resolves [JavaCandidate]s through Java PSI `resolve()` calls; no Kotlin Analysis API session
 * is needed.
 */
internal class JavaCandidateResolver(
    private val index: DeclarationIndex,
    private val stats: MutableResolutionStats,
) {
    private val edges = EdgeRecorder(index, stats)

    fun resolve(candidate: JavaCandidate) {
        when (candidate) {
            is JavaCallCandidate -> resolveCall(candidate.element)

            is JavaTypeRefCandidate -> {
                when (val resolved = candidate.element.resolve()) {
                    is PsiClass -> handleResolved(
                        resolved, candidate.element, candidate.kind,
                    )

                    null -> stats.unresolved++
                    else -> stats.skipped++
                }
            }

            is JavaAnnotationCandidate -> {
                handleResolved(
                    candidate.element.resolveAnnotationType(),
                    candidate.element,
                    EdgeKind.ANNOTATION,
                )
            }

            is JavaPropertyCandidate -> {
                when (val resolved = candidate.element.resolve()) {
                    is PsiField -> handleResolved(
                        resolved.containingClass,
                        candidate.element,
                        EdgeKind.PROPERTY_ACCESS,
                    )

                    is PsiClass -> handleResolved(
                        resolved,
                        candidate.element,
                        EdgeKind.TYPE_REFERENCE,
                    )

                    else -> stats.skipped++
                }
            }

            is JavaMethodRefCandidate -> {
                val method = candidate.element.resolve() as? PsiMethod
                if (method == null) {
                    stats.unresolved++
                    return
                }
                handleResolved(
                    method.containingClass,
                    candidate.element,
                    EdgeKind.CALLABLE_REFERENCE,
                )
            }

            is JavaLambdaCandidate -> {
                val resolved =
                    (candidate.element.functionalInterfaceType as? PsiClassType)?.resolve()
                handleResolved(
                    resolved,
                    candidate.element,
                    EdgeKind.OPERATOR_OR_SYNTHETIC_CALL,
                )
            }
        }
    }

    private fun resolveCall(element: PsiElement) {
        when (element) {
            is PsiNewExpression -> {
                // `new Foo(){}` keeps the referenced class on the anonymous class, and
                // implicit default constructors never resolve through resolveMethod();
                // the declared class reference is the edge target either way.
                val ref = element.anonymousClass?.baseClassReference ?: element.classReference
                val resolved = ref?.resolve() as? PsiClass
                if (resolved == null) {
                    stats.unresolved++
                    return
                }
                handleResolved(
                    resolved,
                    element,
                    EdgeKind.CONSTRUCTOR_CALL,
                )
            }

            is PsiMethodCallExpression -> {
                val refName = element.methodExpression.referenceName
                if (refName == "super" || refName == "this") {
                    // resolveMethod() on constructor chains goes through Cls mirror
                    // services that a standalone session does not have; resolve the
                    // superclass structurally instead.
                    val chained = chainedConstructorTarget(element)
                    if (chained == null) {
                        stats.skipped++
                        return
                    }
                    handleResolved(
                        chained,
                        element,
                        EdgeKind.CONSTRUCTOR_CALL,
                    )
                    return
                }
                val method = element.resolveMethod()
                // Record accessors and other methods this session cannot resolve still
                // name their receiver type; the graph only needs the owning class.
                val target = method?.containingClass ?: qualifierReceiverClass(element)
                if (target == null) {
                    if (method == null) stats.unresolved++ else stats.skipped++
                    return
                }
                val kind = if (method?.isConstructor == true) {
                    EdgeKind.CONSTRUCTOR_CALL
                } else {
                    EdgeKind.FUNCTION_CALL
                }
                handleResolved(
                    target,
                    element,
                    kind,
                )
            }

            else -> stats.skipped++
        }
    }

    private fun qualifierReceiverClass(call: PsiMethodCallExpression): PsiClass? {
        val qualifier = call.methodExpression.qualifierExpression as? PsiReferenceExpression
            ?: return null
        return when (val resolved = qualifier.resolve()) {
            is PsiClass -> resolved
            is PsiVariable -> (resolved.type as? PsiClassType)?.resolve()
            is PsiMethod -> (resolved.returnType as? PsiClassType)?.resolve()
            else -> null
        }
    }

    // `super(...)` and `this(...)` resolve the target structurally: the owner's extends list for
    // `super`, the owner itself for `this` (which then lands in selfEdgesSkipped).
    private fun chainedConstructorTarget(call: PsiMethodCallExpression): PsiClass? {
        val owner = PsiTreeUtil.getParentOfType(call, PsiClass::class.java) ?: return null
        return when (call.methodExpression.referenceName) {
            "super" -> owner.extendsList?.referenceElements?.firstOrNull()?.resolve() as? PsiClass
            else -> owner
        }
    }

    private fun handleResolved(
        psiClass: PsiClass?,
        element: PsiElement,
        kind: EdgeKind,
    ) {
        val fqName = psiClass?.qualifiedName
        if (fqName == null) {
            if (psiClass == null) stats.unresolved++ else stats.skipped++
            return
        }
        val to = index.nodeForQualifiedName(fqName)
        if (to == null) {
            stats.resolvedExternal++
            return
        }
        edges.record(to, element, kind)
    }
}
