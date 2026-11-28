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

internal object JavaReferenceCollector {
    fun collect(file: PsiJavaFile): List<ReferenceCandidate> {
        val result = mutableListOf<ReferenceCandidate>()
        file.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitNewExpression(expression: PsiNewExpression) {
                    if (expression.arrayDimensions.isEmpty() && expression.arrayInitializer == null) {
                        result += JavaCallCandidate(expression)
                    }
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
            parent is PsiReferenceList && parent.role == PsiReferenceList.Role.PERMITS_LIST -> {
                JavaPermitsCandidate(reference)
            }

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

    private fun isNonSourceReference(reference: PsiJavaCodeReferenceElement): Boolean =
        PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase::class.java) != null ||
            PsiTreeUtil.getParentOfType(reference, PsiDocComment::class.java) != null

    private fun isInheritanceReference(parent: PsiElement?): Boolean =
        parent is PsiReferenceList &&
            (
                parent.role == PsiReferenceList.Role.EXTENDS_LIST ||
                    parent.role == PsiReferenceList.Role.IMPLEMENTS_LIST
            )

    private fun isCoveredByEnclosingCandidate(parent: PsiElement?): Boolean =
        parent is PsiNewExpression ||
            parent is PsiMethodCallExpression ||
            parent is PsiMethodReferenceExpression ||
            parent is PsiAnnotation ||
            parent is PsiPackageStatement
}
