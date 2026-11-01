package io.github.klasstree.analyzer

import io.github.klasstree.graph.ModuleSpec
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

internal data class SourceFileInfo(
    val module: ModuleSpec,
    val file: KtFile,
    val absolutePath: Path,
    val relativePath: String,
)
