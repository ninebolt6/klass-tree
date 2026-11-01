package io.github.klasstree.analyzer

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import io.github.klasstree.graph.CallSite
import io.github.klasstree.graph.EdgeKind
import io.github.klasstree.graph.GraphBuilder
import io.github.klasstree.graph.GraphNode
import io.github.klasstree.graph.ModuleSpec
import io.github.klasstree.graph.NodeKind
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.util.IdentityHashMap

/**
 * Builds the declaration nodes for one analysis run and maps PSI elements / resolved symbols to
 * those nodes. Members are aggregated into their owning class; top-level functions and properties
 * are aggregated into a per-file FILE_SCOPE node.
 */
internal class DeclarationIndex(
    private val builder: GraphBuilder,
) {
    private val classNodes = IdentityHashMap<KtClassOrObject, GraphNode>()
    private val classNodesByFqName = HashMap<String, GraphNode>()
    private val typeAliasNodes = IdentityHashMap<KtTypeAlias, GraphNode>()
    private val fileScopeNodes = HashMap<String, GraphNode>()
    private val fileInfoByFile = IdentityHashMap<KtFile, SourceFileInfo>()
    private val documents = IdentityHashMap<KtFile, Document?>()

    fun indexFile(info: SourceFileInfo) {
        val file = info.file
        fileInfoByFile[file] = info

        val hasTopLevelCallable = file.declarations.any { it is KtNamedDeclaration && (it is KtProperty || it is org.jetbrains.kotlin.psi.KtNamedFunction) }
        if (hasTopLevelCallable) {
            fileScopeNode(info)
        }

        file.declarations.forEach { declaration ->
            when (declaration) {
                is KtClassOrObject -> indexClass(declaration, info)
                is KtTypeAlias -> indexTypeAlias(declaration, info)
            }
        }
    }

    fun nodeForElement(element: PsiElement): GraphNode? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtClassOrObject -> classNodes[current]?.let { return it }
                is KtTypeAlias -> typeAliasNodes[current]?.let { return it }
            }
            current = current.parent
        }
        val file = element.containingFile as? KtFile ?: return null
        val info = fileInfoByFile[file] ?: return null
        return fileScopeNodes[info.absolutePath.toString()]
    }

    fun nodeForSymbol(symbol: KaSymbol): GraphNode? {
        val psi = symbol.psi ?: symbol.realPsi ?: symbol.anchorPsi
        if (psi != null) {
            if (psi is KtClassOrObject) {
                classNodes[psi]?.let { return it }
            }
            if (psi is KtTypeAlias) {
                typeAliasNodes[psi]?.let { return it }
            }
            nodeForElement(psi)?.let { return it }
        }

        // Constructors (including implicit primary constructors and annotation constructors)
        // do not always expose their owning class through `psi`. Fall back to the class id so
        // `Foo()` and `@Foo` still connect to the source class node.
        if (symbol is KaConstructorSymbol) {
            val fqName = symbol.containingClassId?.asSingleFqName()?.asString()
            if (fqName != null) classNodesByFqName[fqName]?.let { return it }
        }
        return null
    }

    fun callSite(element: PsiElement): CallSite? {
        val file = element.containingFile as? KtFile ?: return null
        val info = fileInfoByFile[file] ?: return null
        val (line, column) = lineAndColumn(file, element.textOffset)
        if (line == null || column == null) return null
        return CallSite(info.relativePath, line, column)
    }

    fun addEdge(from: GraphNode, to: GraphNode, kind: EdgeKind, site: CallSite) {
        builder.addEdge(from.id, to.id, kind, site)
    }

    private fun indexClass(declaration: KtClassOrObject, info: SourceFileInfo) {
        if (declaration is KtEnumEntry) return
        val node = createClassNode(declaration, info)
        classNodes[declaration] = node
        node.fqName?.let { classNodesByFqName.putIfAbsent(it, node) }
        builder.addNode(node)
        declaration.declarations.forEach { child ->
            when (child) {
                is KtClassOrObject -> indexClass(child, info)
                is KtTypeAlias -> indexTypeAlias(child, info)
            }
        }
    }

    private fun indexTypeAlias(declaration: KtTypeAlias, info: SourceFileInfo) {
        val fqName = declaration.fqName?.asString()
        val name = declaration.name ?: "<anonymous>"
        val offset = declaration.nameIdentifier?.textOffset ?: declaration.textOffset
        val (line, column) = lineAndColumn(info.file, offset)
        val node = GraphNode(
            id = "${info.module.id}:${info.relativePath}#${NodeKind.TYPE_ALIAS.name}:${fqName ?: name}",
            kind = NodeKind.TYPE_ALIAS,
            name = name,
            fqName = fqName,
            module = info.module.moduleLabel,
            sourceSet = info.module.sourceSetName,
            packageName = info.file.packageFqName.asString().ifEmpty { null },
            sourceFile = info.relativePath,
            line = line,
            column = column,
        )
        typeAliasNodes[declaration] = node
        builder.addNode(node)
    }

    private fun createClassNode(declaration: KtClassOrObject, info: SourceFileInfo): GraphNode {
        val kind = when {
            declaration.isAnnotation() -> NodeKind.ANNOTATION
            declaration is KtObjectDeclaration -> NodeKind.OBJECT
            declaration is KtClass && declaration.isInterface() -> NodeKind.INTERFACE
            declaration is KtClass && declaration.isEnum() -> NodeKind.ENUM
            else -> NodeKind.CLASS
        }
        val fqName = declaration.fqName?.asString()
        val name = declaration.name ?: "<anonymous>"
        val offset = declaration.nameIdentifier?.textOffset ?: declaration.textOffset
        val (line, column) = lineAndColumn(info.file, offset)
        return GraphNode(
            id = "${info.module.id}:${info.relativePath}#${kind.name}:${fqName ?: name}",
            kind = kind,
            name = name,
            fqName = fqName,
            module = info.module.moduleLabel,
            sourceSet = info.module.sourceSetName,
            packageName = info.file.packageFqName.asString().ifEmpty { null },
            sourceFile = info.relativePath,
            line = line,
            column = column,
        )
    }

    private fun fileScopeNode(info: SourceFileInfo): GraphNode {
        val key = info.absolutePath.toString()
        return fileScopeNodes.getOrPut(key) {
            val packageName = info.file.packageFqName.asString().ifEmpty { null }
            val node = GraphNode(
                id = "${info.module.id}:${info.relativePath}#${NodeKind.FILE_SCOPE.name}",
                kind = NodeKind.FILE_SCOPE,
                name = info.file.name,
                fqName = packageName,
                module = info.module.moduleLabel,
                sourceSet = info.module.sourceSetName,
                packageName = packageName,
                sourceFile = info.relativePath,
                line = 1,
                column = 1,
            )
            builder.addNode(node)
            node
        }
    }

    private fun document(file: KtFile): Document? {
        if (documents.containsKey(file)) return documents[file]
        val document = file.viewProvider.document
            ?: PsiDocumentManager.getInstance(file.project).getDocument(file)
        documents[file] = document
        return document
    }

    private fun lineAndColumn(file: KtFile, offset: Int): Pair<Int?, Int?> {
        val document = document(file) ?: return null to null
        val safeOffset = offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        return (lineIndex + 1) to (safeOffset - lineStart + 1)
    }
}

internal val ModuleSpec.moduleLabel: String
    get() = projectPath.removePrefix(":").ifEmpty { "root" }
