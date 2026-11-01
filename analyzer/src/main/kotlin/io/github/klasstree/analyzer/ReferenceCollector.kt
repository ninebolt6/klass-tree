package io.github.klasstree.analyzer

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolution.KtResolvableCall

internal sealed interface ReferenceCandidate {
    val element: PsiElement
}

internal data class TypeReferenceCandidate(override val element: KtUserType) : ReferenceCandidate
internal data class InheritanceCandidate(override val element: KtSuperTypeListEntry) : ReferenceCandidate
internal data class CallCandidate(override val element: KtCallExpression) : ReferenceCandidate
internal data class AnnotationCandidate(override val element: KtAnnotationEntry) : ReferenceCandidate
internal data class PropertyCandidate(override val element: PsiElement) : ReferenceCandidate
internal data class CallableReferenceCandidate(override val element: KtCallableReferenceExpression) : ReferenceCandidate
internal data class OperatorCandidate(override val element: KtResolvableCall) : ReferenceCandidate

/**
 * Enumerates PSI locations that may represent dependencies. Resolution is deliberately kept out
 * of this visitor: all candidates are collected first, then resolved inside a single `analyze`
 * block per file.
 */
internal object ReferenceCollector {
    fun collect(file: org.jetbrains.kotlin.psi.KtFile): List<ReferenceCandidate> {
        val result = mutableListOf<ReferenceCandidate>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitUserType(type: KtUserType) {
                if (shouldCollectType(type)) result += TypeReferenceCandidate(type)
                super.visitUserType(type)
            }

            override fun visitSuperTypeListEntry(superTypeListEntry: KtSuperTypeListEntry) {
                result += InheritanceCandidate(superTypeListEntry)
                super.visitSuperTypeListEntry(superTypeListEntry)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                // A supertype constructor call is already represented by INHERITANCE. The call
                // expression of an annotation is represented by ANNOTATION.
                val isSuperTypeCall = expression.getStrictParentOfType<KtSuperTypeListEntry>() != null
                val isAnnotationCall = expression.parent is KtAnnotationEntry
                if (!isSuperTypeCall && !isAnnotationCall) {
                    result += CallCandidate(expression)
                }
                super.visitCallExpression(expression)
            }

            override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                result += AnnotationCandidate(annotationEntry)
                super.visitAnnotationEntry(annotationEntry)
            }

            override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
                if (shouldCollectQualified(expression)) result += PropertyCandidate(expression)
                super.visitQualifiedExpression(expression)
            }

            override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
                result += CallableReferenceCandidate(expression)
                super.visitCallableReferenceExpression(expression)
            }

            override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                result += OperatorCandidate(expression)
                super.visitArrayAccessExpression(expression)
            }

            override fun visitReferenceExpression(expression: org.jetbrains.kotlin.psi.KtReferenceExpression) {
                when (expression) {
                    is KtOperationReferenceExpression -> result += OperatorCandidate(expression)
                    is KtNameReferenceExpression -> if (shouldCollectName(expression)) {
                        result += PropertyCandidate(expression)
                    }
                }
                super.visitReferenceExpression(expression)
            }
        })
        return result
    }

    private fun shouldCollectType(type: KtUserType): Boolean {
        // Only the outermost user type of a qualified name; resolving every segment would turn
        // package qualifiers into spurious edges.
        if (type.parent is KtUserType) return false
        if (type.getStrictParentOfType<KtImportDirective>() != null) return false

        val typeReference = type.getStrictParentOfType<KtTypeReference>()
        // The head type of a supertype is represented by INHERITANCE instead.
        if (typeReference?.parent is KtSuperTypeListEntry) return false
        // An annotation entry is represented by ANNOTATION instead.
        if (type.getStrictParentOfType<KtAnnotationEntry>() != null) return false
        return true
    }

    private fun shouldCollectName(name: KtNameReferenceExpression): Boolean {
        val parent = name.parent
        if (parent is KtUserType || parent is KtImportDirective) return false
        if (parent is KtCallExpression && parent.calleeExpression === name) return false
        if (parent is KtQualifiedExpression && parent.selectorExpression === name) return false
        if (parent is KtCallableReferenceExpression) return false
        if (parent is KtAnnotationEntry) return false
        if (parent is KtLabelReferenceExpression) return false
        if (parent is KtValueArgument && parent.getArgumentName()?.referenceExpression === name) return false
        if (name.getStrictParentOfType<KtImportDirective>() != null) return false
        if (name.getReferencedName() == "this" || name.getReferencedName() == "super") return false
        return true
    }

    private fun shouldCollectQualified(expression: KtQualifiedExpression): Boolean {
        val parent = expression.parent
        if (parent is KtCallExpression && parent.calleeExpression === expression) return false
        if (expression.getStrictParentOfType<KtImportDirective>() != null) return false
        return true
    }
}
