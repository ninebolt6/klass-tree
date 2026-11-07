package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.util.PsiTreeUtil

/**
 * Enumerates Java PSI locations that may represent dependencies, mirroring [ReferenceCollector]'s
 * role for Kotlin files. Element kinds are not resolved here: candidates are classified when they
 * are resolved inside the `analyze` block.
 */
internal object JavaReferenceCollector {
    fun collect(file: PsiJavaFile): List<ReferenceCandidate> {
        val result = mutableListOf<ReferenceCandidate>()
        file.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitNewExpression(expression: PsiNewExpression) {
                    result += JavaCallCandidate(expression)
                    super.visitNewExpression(expression)
                }

                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    result += JavaCallCandidate(expression)
                    super.visitMethodCallExpression(expression)
                }

                override fun visitAnnotation(annotation: PsiAnnotation) {
                    result += JavaAnnotationCandidate(annotation)
                    super.visitAnnotation(annotation)
                }

                override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                    result += JavaMethodRefCandidate(expression)
                    super.visitMethodReferenceExpression(expression)
                }

                override fun visitLambdaExpression(expression: PsiLambdaExpression) {
                    result += JavaLambdaCandidate(expression)
                    super.visitLambdaExpression(expression)
                }

                // PsiReferenceExpression (fields, variables, qualified names) is a subtype and is
                // visited through this callback as well.
                override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                    collectReferenceElement(reference)?.let { result += it }
                    super.visitReferenceElement(reference)
                }
            },
        )
        return result
    }

    private fun collectReferenceElement(reference: PsiJavaCodeReferenceElement): ReferenceCandidate? {
        if (isNonSourceReference(reference)) return null
        val parent = reference.parent
        return when {
            isInheritanceReference(parent) -> {
                JavaInheritanceCandidate(reference)
            }

            isCoveredByEnclosingCandidate(parent) -> {
                null
            }

            parent is PsiTypeElement || parent is PsiClassObjectAccessExpression -> {
                JavaTypeCandidate(reference)
            }

            reference is PsiReferenceExpression -> {
                JavaPropertyCandidate(reference)
            }

            else -> {
                JavaTypeCandidate(reference)
            }
        }
    }

    // Import qualifiers and `{@link ...}` javadoc references are documentation or
    // name resolution aids, not dependencies.
    private fun isNonSourceReference(reference: PsiJavaCodeReferenceElement): Boolean =
        PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase::class.java) != null ||
            PsiTreeUtil.getParentOfType(reference, PsiDocComment::class.java) != null

    private fun isInheritanceReference(parent: PsiElement?): Boolean =
        parent is PsiReferenceList &&
            (
                parent.role == PsiReferenceList.Role.EXTENDS_LIST ||
                    parent.role == PsiReferenceList.Role.IMPLEMENTS_LIST
            )

    // `new Foo()` yields CONSTRUCTOR_CALL via the enclosing PsiNewExpression, the
    // qualifier of a call (`Foo.bar()`) is covered by that call's edge, and annotations,
    // method references and package statements are collected as their own candidates.
    private fun isCoveredByEnclosingCandidate(parent: PsiElement?): Boolean =
        parent is PsiNewExpression ||
            parent is PsiMethodCallExpression ||
            parent is PsiMethodReferenceExpression ||
            parent is PsiAnnotation ||
            parent is PsiPackageStatement
}
