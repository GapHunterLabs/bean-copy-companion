package dev.gaphunter.beancopycompanion.generate

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InMemoryValidatorTest : BasePlatformTestCase() {

    fun testFindsNoErrorInValidJavaText() {
        val text = """
            public final class Acme {
                private Acme() {}
                public static int total(int a, int b) { return a + b; }
            }
        """.trimIndent()

        assertNull(InMemoryValidator.findFirstSyntaxError(project, text, "Acme.java"))
    }

    fun testFindsASyntaxErrorInMalformedJavaText() {
        val malformed = """
            public final class Acme {
                public static int total(int a, int b) {
            }
        """.trimIndent()

        assertNotNull(InMemoryValidator.findFirstSyntaxError(project, malformed, "Acme.java"))
    }

    fun testFindsNoErrorInValidKotlinText() {
        val text = """
            fun total(a: Int, b: Int): Int = a + b
        """.trimIndent()

        assertNull(InMemoryValidator.findFirstSyntaxError(project, text, "Acme.kt"))
    }

    fun testFindsASyntaxErrorInMalformedKotlinText() {
        val malformed = """
            fun total(a: Int, b: Int( : Int = a + b
        """.trimIndent()

        assertNotNull(InMemoryValidator.findFirstSyntaxError(project, malformed, "Acme.kt"))
    }
}
