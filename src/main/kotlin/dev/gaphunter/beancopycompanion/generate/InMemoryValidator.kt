package dev.gaphunter.beancopycompanion.generate

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * The diff between this plugin and the competitor it replaces (real
 * review quoted verbatim: a `PsiMethod.getProject()` NullPointerException
 * crash, and "直接报错不能用" -- "straight up errors, unusable"): generated
 * text is parsed into a throwaway, never-persisted PSI file and checked
 * for syntax errors BEFORE it's ever offered to the user as something to
 * write to disk. Uses [PsiFileFactory.createFileFromText] -- the exact
 * "sandbox PSI" mechanism already proven in refactor-simulator and
 * Test Scaffold Companion's own `InMemoryValidator`: the file this
 * creates is never added to a [com.intellij.psi.PsiDirectory], so it
 * carries zero risk to the real project regardless of what it contains.
 *
 * Picks [JavaLanguage] or [KotlinLanguage] based on [fileName]'s own
 * extension -- [CopierWriter] always names the file to match the
 * language it actually rendered, so this stays a pure function of the
 * name rather than needing a second language flag threaded through.
 *
 * Layer 1 check only: syntax validity (no [PsiErrorElement] anywhere in
 * the tree), not semantic/type resolution -- same documented scope as
 * Test Scaffold Companion's validator of the same name. Does NOT call
 * `PsiManager.dropPsiCaches()` -- that requires the EDT specifically
 * (confirmed by a real `runIde` crash on Test Scaffold Companion, see
 * `INTELLIJ_PLATFORM_KNOWLEDGE.md`) and [PsiFileFactory.createFileFromText]
 * already returns a fully-parsed tree, so it was never necessary.
 */
object InMemoryValidator {

    /**
     * Null return means "safe to write, no syntax errors found". A
     * non-null return is the first error's own human-readable text, for
     * surfacing to the user as an honest "could not generate safely
     * here" outcome -- never silently write text that failed this check.
     */
    fun findFirstSyntaxError(project: Project, generatedText: String, fileName: String): String? {
        val language = if (fileName.endsWith(".kt")) KotlinLanguage.INSTANCE else JavaLanguage.INSTANCE
        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(fileName, language, generatedText, /* eventSystemEnabled = */ false, /* markAsCopy = */ true)
        val firstError = PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java)
        return firstError?.errorDescription
    }
}
