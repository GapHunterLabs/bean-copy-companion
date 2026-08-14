package dev.gaphunter.beancopycompanion.util

import com.intellij.psi.PsiClassOwner
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LanguageDetectorTest : BasePlatformTestCase() {

    fun testJavaClassIsNotKotlin() {
        val file = myFixture.addFileToProject("Plain.java", "public class Plain {}") as PsiClassOwner
        assertFalse(LanguageDetector.isKotlinClass(file.classes.first()))
    }

    fun testKotlinClassIsDetectedAsKotlin() {
        val file = myFixture.addFileToProject("Plain.kt", "class Plain") as PsiClassOwner
        assertTrue(LanguageDetector.isKotlinClass(file.classes.first()))
    }
}
