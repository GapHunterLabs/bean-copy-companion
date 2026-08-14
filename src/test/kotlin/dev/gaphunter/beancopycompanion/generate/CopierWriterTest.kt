package dev.gaphunter.beancopycompanion.generate

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.beancopycompanion.match.FieldMatcher
import dev.gaphunter.beancopycompanion.match.TargetAssigner
import dev.gaphunter.beancopycompanion.util.LanguageDetector

/**
 * The actual product claim under test, same as Test Scaffold Companion's
 * validator test of the same shape: every rendered scenario below is
 * fed straight into [InMemoryValidator] and must come back clean. A
 * plugin that generates code which doesn't parse is exactly the
 * documented complaint against the competitor this plugin replaces
 * ("直接报错不能用" -- "straight up errors, unusable").
 */
class CopierWriterTest : BasePlatformTestCase() {

    private fun classOf(file: PsiFile): PsiClass = (file as PsiClassOwner).classes.first()

    private fun renderAndValidate(source: PsiClass, target: PsiClass): Pair<RenderedCopier, String?> {
        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))
        val packageName = (target.containingFile as? PsiClassOwner)?.packageName
        val rendered = CopierWriter.render(plan, packageName)
        val fileName = rendered.fileName + if (LanguageDetector.isKotlinClass(target)) ".kt" else ".java"
        val error = InMemoryValidator.findFirstSyntaxError(project, rendered.text, fileName)
        return rendered to error
    }

    fun testJavaToJavaNoArgsSettersProducesValidJavaCopier() {
        val source = classOf(myFixture.addFileToProject(
            "JS1.java",
            """
            public class JS1 {
                private String name;
                public String getName() { return name; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "JT1.java",
            """
            public class JT1 {
                private String name;
                public void setName(String name) { this.name = name; }
            }
            """.trimIndent(),
        ))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        assertTrue(rendered.text.contains("public static JT1 copy(JS1 source)"))
        assertTrue(rendered.text.contains("target.setName(source.getName());"))
    }

    fun testKotlinToKotlinConstructorProducesValidKotlinCopierUsingPropertySyntax() {
        val source = classOf(myFixture.addFileToProject("KS1.kt", "class KS1(val label: String, val count: Int)"))
        val target = classOf(myFixture.addFileToProject("KT1.kt", "data class KT1(val label: String, val count: Int)"))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        assertTrue(rendered.text.contains("fun KS1.toKT1(): KT1"))
        // Kotlin-to-Kotlin: property syntax, never a synthetic getXxx() call.
        assertTrue(rendered.text.contains("this.label"))
        assertTrue(rendered.text.contains("this.count"))
        assertFalse(rendered.text.contains("getLabel"))
    }

    fun testJavaSourceIntoKotlinTargetUsesMethodCallToReadTheJavaGetter() {
        val source = classOf(myFixture.addFileToProject(
            "JS2.java",
            """
            public class JS2 {
                private String label;
                public String getLabel() { return label; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject("KT2.kt", "data class KT2(val label: String)"))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        // Generated file is Kotlin (matches the TARGET's language)...
        assertTrue(rendered.text.contains("fun JS2.toKT2(): KT2"))
        // ...but the source is Java, so it must call the real getter method, not synthetic property syntax.
        assertTrue(rendered.text.contains("this.getLabel()"))
    }

    fun testKotlinSourceIntoJavaTargetProducesAJavaCopierCallingTheSynthesizedGetter() {
        val source = classOf(myFixture.addFileToProject("KS3.kt", "class KS3(val label: String)"))
        val target = classOf(myFixture.addFileToProject(
            "JT3.java",
            """
            public class JT3 {
                private String label;
                public void setLabel(String label) { this.label = label; }
            }
            """.trimIndent(),
        ))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        assertTrue(rendered.text.contains("public static JT3 copy(KS3 source)"))
        assertTrue(rendered.text.contains("source.getLabel()"))
    }

    fun testUnmappedFieldBecomesAnHonestTodoCommentAndStillValidates() {
        val source = classOf(myFixture.addFileToProject("JS4.java", "public class JS4 {}"))
        val target = classOf(myFixture.addFileToProject(
            "JT4.java",
            """
            public class JT4 {
                private String missing;
                public void setMissing(String missing) { this.missing = missing; }
            }
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))
        val rendered = CopierWriter.render(plan, null)
        val error = InMemoryValidator.findFirstSyntaxError(project, rendered.text, rendered.fileName + ".java")

        assertNull("a TODO-only body must still be syntactically valid, got: $error", error)
        assertTrue(rendered.text.contains("TODO(bean-copy): 'missing'"))
    }

    fun testLombokBuilderTargetProducesAFluentBuilderChain() {
        val source = classOf(myFixture.addFileToProject(
            "JS5.java",
            """
            public class JS5 {
                private String label;
                public String getLabel() { return label; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "JT5.java",
            """
            @lombok.Builder
            public class JT5 {
                private final String label;
                private JT5(String label) { this.label = label; }
                public static JT5Builder builder() { return new JT5Builder(); }

                public static class JT5Builder {
                    private String label;
                    public JT5Builder label(String label) { this.label = label; return this; }
                    public JT5 build() { return new JT5(label); }
                }
            }
            """.trimIndent(),
        ))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        assertTrue(rendered.text.contains("JT5.builder()"))
        assertTrue(rendered.text.contains(".label(source.getLabel())"))
        assertTrue(rendered.text.contains(".build();"))
    }

    fun testConstructorPlaceholderForAnUncoveredParamStillValidates() {
        val source = classOf(myFixture.addFileToProject("KS6.kt", "class KS6(val a: String)"))
        val target = classOf(myFixture.addFileToProject("KT6.kt", "data class KT6(val a: String, val b: Int)"))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        assertTrue(rendered.text.contains("TODO(bean-copy): no source value for constructor param 'b'"))
    }

    /**
     * Real regression, found live in a runIde sandbox (2026-08-13), not
     * by any test that existed before this one: [testConstructorPlaceholderForAnUncoveredParamStillValidates]
     * only ever put the placeholder LAST in the constructor's parameter
     * list, where the old (buggy) comma-after-every-line rendering
     * never actually appended a trailing separator. The real demo
     * scenario (Java `Order` -> Kotlin `OrderDto`) has the unmapped
     * param in the MIDDLE (`id`, `customer`, `total`) -- exactly the
     * shape that exposed the bug: the separator comma landed inside the
     * placeholder's own `// TODO` line comment, invisible to the
     * parser, and `InMemoryValidator` correctly refused to write it
     * ("Expecting ','"). This test pins the middle-placeholder shape
     * specifically so this exact class of bug can't recur silently.
     */
    fun testConstructorPlaceholderInTheMiddleOfTheArgumentListStillValidates() {
        val source = classOf(myFixture.addFileToProject(
            "JS7.java",
            """
            public class JS7 {
                private long id;
                private double total;
                public long getId() { return id; }
                public double getTotal() { return total; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "KT7.kt",
            """
            data class KT7(val id: Long, val customer: String, val total: Double)
            """.trimIndent(),
        ))

        val (rendered, error) = renderAndValidate(source, target)

        assertNull("expected no syntax error, got: $error", error)
        assertTrue(rendered.text.contains("TODO(bean-copy): no source value for constructor param 'customer'"))
        // The line right after the placeholder (the 'total' arg) must be a
        // real, separate argument -- not swallowed into the TODO comment.
        assertTrue(rendered.text.contains("this.getTotal()"))
    }

    /**
     * Real regression found live (`runIde`, 2026-08-13) in the demo's
     * own hero scenario (`Order` -> `OrderDto`): the standalone
     * unmapped-fields loop printed a SECOND `// TODO` for 'customer',
     * identical in meaning to the one already inline in the
     * constructor call -- same information, twice, in the same file.
     * Text must appear exactly once; the field must still be counted
     * in `plan.unmapped` (see `TargetAssignerTest.
     * testFieldCoveredByAConstructorPlaceholderIsStillCountedInUnmapped`
     * for why the COUNT must never be the thing that changes).
     */
    fun testUnmappedFieldCoveredByAConstructorPlaceholderIsNotRepeatedInTheGeneratedText() {
        val source = classOf(myFixture.addFileToProject(
            "JS8.java",
            """
            public class JS8 {
                private long id;
                private double total;
                public long getId() { return id; }
                public double getTotal() { return total; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "KT8.kt",
            """
            data class KT8(val id: Long, val customer: String, val total: Double)
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))
        assertTrue("'customer' must still be counted for the caller's notification", plan.unmapped.any { it.targetField.name == "customer" })

        val rendered = CopierWriter.render(plan, null)
        val occurrences = Regex("'customer'").findAll(rendered.text).count()
        assertEquals("'customer' should be mentioned exactly once in the generated text, not repeated", 1, occurrences)
    }
}
