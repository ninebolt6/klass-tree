package io.github.klasstree.analyzer

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import io.github.klasstree.graph.CallSite
import io.github.klasstree.graph.EdgeKind
import io.github.klasstree.graph.GraphBuilder
import io.github.klasstree.graph.GraphNode
import io.github.klasstree.graph.ModuleSpec
import io.github.klasstree.graph.NodeKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
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
    private val classNodes = IdentityHashMap<PsiElement, GraphNode>()
    private val classNodesByFqName = HashMap<String, GraphNode>()
    private val typeAliasNodes = IdentityHashMap<KtTypeAlias, GraphNode>()
    private val fileScopeNodes = HashMap<String, GraphNode>()
    private val fileScopeAliases = HashMap<String, GraphNode>()
    private val fileInfoByFile = IdentityHashMap<PsiFile, SourceFileInfo>()
    private val documents = IdentityHashMap<PsiFile, Document?>()

    fun indexFile(info: SourceFileInfo) {
        val file = info.file
        fileInfoByFile[file] = info
        when (file) {
            is KtFile -> indexKotlinFile(file, info)
            is PsiJavaFile -> indexJavaFile(file, info)
        }
    }

    private fun indexKotlinFile(file: KtFile, info: SourceFileInfo) {
        val hasTopLevelCallable =
            file.declarations.any { it is KtProperty || it is KtNamedFunction }
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

    private fun indexJavaFile(file: PsiJavaFile, info: SourceFileInfo) {
        file.classes.forEach { indexJavaClass(it, info) }
    }

    private fun indexJavaClass(psiClass: PsiClass, info: SourceFileInfo) {
        val kind = when {
            psiClass.isAnnotationType -> NodeKind.ANNOTATION
            psiClass.isEnum -> NodeKind.ENUM
            psiClass.isInterface -> NodeKind.INTERFACE
            else -> NodeKind.CLASS
        }
        val fqName = psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>"
        val name = psiClass.name ?: "<anonymous>"
        val offset = psiClass.nameIdentifier?.textOffset ?: psiClass.textOffset
        val node = newNode(info, kind, name, fqName, offset)
        classNodes[psiClass] = node
        classNodesByFqName.putIfAbsent(fqName, node)
        builder.addNode(node)
        psiClass.innerClasses.forEach { indexJavaClass(it, info) }
    }

    fun nodeForElement(element: PsiElement): GraphNode? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is KtClassOrObject -> classNodes[current]?.let { return it }
                is KtTypeAlias -> typeAliasNodes[current]?.let { return it }
                is PsiClass -> classNodes[current]?.let { return it }
            }
            current = current.parent
        }
        val file = element.containingFile ?: return null
        val info = fileInfoByFile[file] ?: return null
        return fileScopeNodes[info.absolutePath.toString()]
    }

    fun nodeForQualifiedName(fqName: String): GraphNode? =
        classNodesByFqName[fqName] ?: fileScopeAliases[fqName]

    fun nodeForSymbol(symbol: KaSymbol): GraphNode? {
        val psi = symbol.psi ?: symbol.realPsi ?: symbol.anchorPsi
        // nodeForElement walks the PSI parents, covering declared classes and aliases as well as
        // member symbols belonging to an indexed declaration.
        if (psi != null) nodeForElement(psi)?.let { return it }

        // Constructors (including implicit primary constructors and annotation constructors)
        // do not always expose their owning class through `psi`. Fall back to the class id so
        // `Foo()` and `@Foo` still connect to the source class node. The same lookup covers Java
        // classes, whose light symbols do not lead back to the PsiClass node either.
        val fqName = (symbol as? KaClassLikeSymbol)?.classId?.asSingleFqName()?.asString()
            ?: (symbol as? KaConstructorSymbol)?.containingClassId?.asSingleFqName()?.asString()
        if (fqName != null) nodeForQualifiedName(fqName)?.let { return it }
        return null
    }

    fun callSite(element: PsiElement): CallSite? {
        val file = element.containingFile ?: return null
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
        val node = newNode(info, NodeKind.TYPE_ALIAS, name, fqName, offset)
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
        return newNode(info, kind, name, fqName, offset)
    }

    private fun newNode(
        info: SourceFileInfo,
        kind: NodeKind,
        name: String,
        fqName: String?,
        offset: Int,
    ): GraphNode {
        val (line, column) = lineAndColumn(info.file, offset)
        return GraphNode(
            id = "${info.module.id}:${info.relativePath}#${kind.name}:${fqName ?: name}",
            kind = kind,
            name = name,
            fqName = fqName,
            module = info.module.moduleLabel,
            sourceSet = info.module.sourceSetName,
            packageName = info.packageName().ifEmpty { null },
            sourceFile = info.relativePath,
            line = line,
            column = column,
        )
    }

    private fun fileScopeNode(info: SourceFileInfo): GraphNode {
        val key = info.absolutePath.toString()
        return fileScopeNodes.getOrPut(key) {
            val packageName = info.packageName().ifEmpty { null }
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
            // Java sources see Kotlin top-level members through the file facade class
            // (`K.kt` -> `com.example.KKt`); alias the facade name back to this node.
            if (packageName != null && info.file.name.endsWith(".kt")) {
                val facade = (info.file as? KtFile)?.jvmNameAnnotation()
                    ?: "${info.file.name.removeSuffix(".kt")}Kt"
                fileScopeAliases["$packageName.$facade"] = node
            }
            node
        }
    }

    private fun KtFile.jvmNameAnnotation(): String? =
        annotationEntries
            .firstOrNull { it.shortName?.asString() == "JvmName" }
            ?.valueArguments?.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"")

    private fun SourceFileInfo.packageName(): String = when (val file = file) {
        is KtFile -> file.packageFqName.asString()
        is PsiJavaFile -> file.packageName
        else -> ""
    }

    private fun document(file: PsiFile): Document? {
        if (documents.containsKey(file)) return documents[file]
        val document = file.viewProvider.document
            ?: PsiDocumentManager.getInstance(file.project).getDocument(file)
        documents[file] = document
        return document
    }

    private fun lineAndColumn(file: PsiFile, offset: Int): Pair<Int?, Int?> {
        val document = document(file) ?: return null to null
        val safeOffset = offset.coerceIn(0, document.textLength)
        val lineIndex = document.getLineNumber(safeOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        return (lineIndex + 1) to (safeOffset - lineStart + 1)
    }
}

internal val ModuleSpec.moduleLabel: String
    get() = projectPath.removePrefix(":").ifEmpty { "root" }
