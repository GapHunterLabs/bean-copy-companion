package dev.gaphunter.beancopycompanion.util

import com.intellij.psi.PsiClass

/**
 * Whether a [PsiClass] originates from a `.kt` file -- checked on the
 * CONTAINING FILE, never on [PsiClass.getLanguage] itself. A Kotlin
 * class's [PsiClass] view is a light wrapper (`KtLightClass`) built on
 * top of a Java-shaped API for interop; relying on its own reported
 * language would be trusting an implementation detail of that wrapper
 * instead of the one thing that's unambiguous -- the file it was
 * actually declared in. Two independent signals (language id + file
 * extension) are checked so a null/unusual `virtualFile` (e.g. an
 * in-memory test fixture) doesn't silently misclassify a class.
 */
object LanguageDetector {

    fun isKotlinClass(psiClass: PsiClass): Boolean {
        val file = psiClass.containingFile ?: return false
        if (file.language.id.equals("kotlin", ignoreCase = true)) return true
        return file.virtualFile?.extension.equals("kt", ignoreCase = true)
    }
}
