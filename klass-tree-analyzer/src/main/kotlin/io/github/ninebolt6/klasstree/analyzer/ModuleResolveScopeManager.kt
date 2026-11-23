package io.github.ninebolt6.klasstree.analyzer

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.ResolveScopeManager
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule

@OptIn(KaPlatformInterface::class)
internal class ModuleResolveScopeManager(
    private val project: Project,
) : ResolveScopeManager() {
    override fun getResolveScope(element: PsiElement): GlobalSearchScope {
        val module = KotlinProjectStructureProvider.getModule(project, element, null)
        return if (module is KaSourceModule) {
            KaResolutionScopeProvider.getInstance(project).getResolutionScope(module)
        } else {
            GlobalSearchScope.allScope(project)
        }
    }

    override fun getDefaultResolveScope(file: VirtualFile): GlobalSearchScope =
        PsiManager.getInstance(project).findFile(file)?.let { getResolveScope(it) }
            ?: GlobalSearchScope.allScope(project)

    override fun getUseScope(element: PsiElement): GlobalSearchScope = GlobalSearchScope.allScope(project)
}
