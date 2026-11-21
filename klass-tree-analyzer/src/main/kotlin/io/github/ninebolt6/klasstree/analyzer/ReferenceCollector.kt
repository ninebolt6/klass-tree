package io.github.ninebolt6.klasstree.analyzer

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiReferenceExpression
import io.github.ninebolt6.klasstree.graph.EdgeKind
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLabelReferenceExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolution.KtResolvable
import org.jetbrains.kotlin.resolution.KtResolvableCall

internal sealed interface ReferenceCandidate {
    val element: PsiElement
}

internal sealed interface KotlinSymbolCandidate : ReferenceCandidate {
    override val element: KtResolvable
    val kind: EdgeKind
}

internal data class TypeReferenceCandidate(
    override val element: KtUserType,
) : KotlinSymbolCandidate {
    override val kind: EdgeKind get() = EdgeKind.TYPE_REFERENCE
}

internal data class InheritanceCandidate(
    override val element: KtSuperTypeListEntry,
) : KotlinSymbolCandidate {
    override val kind: EdgeKind get() = EdgeKind.INHERITANCE
}

internal data class CallCandidate(
    override val element: KtCallExpression,
) : ReferenceCandidate

// The annotation-specific resolveSuccessfulSymbol overload returns the constructor rather than the class.
internal data class AnnotationCandidate(
    override val element: KtAnnotationEntry,
) : ReferenceCandidate

internal data class PropertyCandidate(
    override val element: PsiElement,
) : ReferenceCandidate

internal data class CallableReferenceCandidate(
    override val element: KtCallableReferenceExpression,
) : ReferenceCandidate

internal data class OperatorCandidate(
    override val element: KtResolvableCall,
) : ReferenceCandidate

internal sealed interface JavaCandidate : ReferenceCandidate

internal data class JavaCallCandidate(
    override val element: PsiElement,
) : JavaCandidate

internal sealed interface JavaTypeRefCandidate : JavaCandidate {
    override val element: PsiJavaCodeReferenceElement
    val kind: EdgeKind
}

internal data class JavaTypeCandidate(
    override val element: PsiJavaCodeReferenceElement,
) : JavaTypeRefCandidate {
    override val kind: EdgeKind get() = EdgeKind.TYPE_REFERENCE
}

internal data class JavaInheritanceCandidate(
    override val element: PsiJavaCodeReferenceElement,
) : JavaTypeRefCandidate {
    override val kind: EdgeKind get() = EdgeKind.INHERITANCE
}

internal data class JavaAnnotationCandidate(
    override val element: PsiAnnotation,
) : JavaCandidate

internal data class JavaPropertyCandidate(
    override val element: PsiReferenceExpression,
) : JavaCandidate

internal data class JavaMethodRefCandidate(
    override val element: PsiMethodReferenceExpression,
) : JavaCandidate

internal data class JavaLambdaCandidate(
    override val element: PsiLambdaExpression,
) : JavaCandidate

internal object ReferenceCollector {
    fun collect(file: KtFile): List<ReferenceCandidate> {
        val result = mutableListOf<ReferenceCandidate>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitUserType(type: KtUserType) {
                    if (shouldCollectType(type)) result += TypeReferenceCandidate(type)
                    super.visitUserType(type)
                }

                override fun visitSuperTypeListEntry(superTypeListEntry: KtSuperTypeListEntry) {
                    result += InheritanceCandidate(superTypeListEntry)
                    super.visitSuperTypeListEntry(superTypeListEntry)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    if (expression.parent !is KtAnnotationEntry) {
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

                override fun visitPropertyDelegate(propertyDelegate: KtPropertyDelegate) {
                    result += OperatorCandidate(propertyDelegate)
                    super.visitPropertyDelegate(propertyDelegate)
                }

                override fun visitForExpression(forExpression: KtForExpression) {
                    result += OperatorCandidate(forExpression)
                    super.visitForExpression(forExpression)
                }

                override fun visitDestructuringDeclarationEntry(entry: KtDestructuringDeclarationEntry) {
                    result += OperatorCandidate(entry)
                    super.visitDestructuringDeclarationEntry(entry)
                }

                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    when (expression) {
                        is KtOperationReferenceExpression -> {
                            if (expression.text !in NON_CALLABLE_OPERATORS) {
                                result += OperatorCandidate(expression)
                            }
                        }

                        is KtNameReferenceExpression -> {
                            if (shouldCollectName(expression)) {
                                result += PropertyCandidate(expression)
                            }
                        }
                    }
                    super.visitReferenceExpression(expression)
                }
            },
        )
        return result
    }

    private val NON_CALLABLE_OPERATORS = setOf("is", "!is", "as", "as?", "&&", "||", "?:", "!!")

    private fun shouldCollectType(type: KtUserType): Boolean =
        when {
            type.parent is KtUserType -> false
            type.getStrictParentOfType<KtImportDirective>() != null -> false
            type.getStrictParentOfType<KtTypeReference>()?.parent is KtSuperTypeListEntry -> false
            type.getStrictParentOfType<KtAnnotationEntry>() != null -> false
            else -> true
        }

    private fun shouldCollectName(name: KtNameReferenceExpression): Boolean =
        !isNameCoveredElsewhere(name, name.parent) &&
            name.getStrictParentOfType<KtImportDirective>() == null &&
            name.getReferencedName() != "this" &&
            name.getReferencedName() != "super"

    private fun isNameCoveredElsewhere(
        name: KtNameReferenceExpression,
        parent: PsiElement?,
    ): Boolean =
        when {
            parent is KtUserType || parent is KtImportDirective -> true
            parent is KtCallExpression && parent.calleeExpression === name -> true
            parent is KtQualifiedExpression && parent.selectorExpression === name -> true
            parent is KtCallableReferenceExpression -> true
            parent is KtAnnotationEntry -> true
            parent is KtLabelReferenceExpression -> true
            parent is KtValueArgument && parent.getArgumentName()?.referenceExpression === name -> true
            else -> false
        }

    private fun shouldCollectQualified(expression: KtQualifiedExpression): Boolean {
        val parent = expression.parent
        return when {
            expression.selectorExpression is KtCallExpression -> false
            parent is KtCallExpression && parent.calleeExpression === expression -> false
            expression.getStrictParentOfType<KtImportDirective>() != null -> false
            else -> true
        }
    }
}
