package io.github.klasstree.analyzer

import com.intellij.psi.PsiFile
import io.github.klasstree.graph.ModuleSpec
import java.nio.file.Path

internal data class SourceFileInfo(
    val module: ModuleSpec,
    val file: PsiFile,
    val absolutePath: Path,
    val relativePath: String,
)
