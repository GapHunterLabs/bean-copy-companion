package dev.gaphunter.beancopycompanion.match

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.beancopycompanion.model.AssignmentStrategy
import dev.gaphunter.beancopycompanion.model.ConstructionStrategy
import dev.gaphunter.beancopycompanion.model.ConstructorArg

class TargetAssignerTest : BasePlatformTestCase() {

    private fun classOf(file: PsiFile): PsiClass = (file as PsiClassOwner).classes.first()

    fun testMutableJavaBeanUsesNoArgsConstructorThenSetters() {
        val source = classOf(myFixture.addFileToProject(
            "S1.java",
            """
            public class S1 {
                private String name;
                public String getName() { return name; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "T1.java",
            """
            public class T1 {
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertEquals(ConstructionStrategy.NO_ARGS_THEN_SETTERS, plan.construction)
        assertEquals(1, plan.mapped.size)
        assertTrue(plan.mapped.first().strategy is AssignmentStrategy.Setter)
    }

    fun testKotlinDataClassWithoutNoArgsConstructorUsesConstructorParams() {
        val source = classOf(myFixture.addFileToProject(
            "S2.kt",
            """
            class S2(val a: String, val b: Int)
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "T2.kt",
            """
            data class T2(val a: String, val b: Int)
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertEquals(ConstructionStrategy.CONSTRUCTOR, plan.construction)
        assertEquals(2, plan.constructorArgs.size)
        assertTrue(plan.constructorArgs.all { it is ConstructorArg.Mapped })
        assertTrue(plan.unmapped.isEmpty())
    }

    /**
     * Real duplication found live (runIde, 2026-08-13): a constructor
     * param with no matching source field showed up TWICE in the
     * generated file -- once as its own inline TODO next to the
     * [ConstructorArg.Placeholder], and again as a separate standalone
     * TODO from [dev.gaphunter.beancopycompanion.model.CopyPlan.unmapped]
     * repeating the exact same field. `plan.unmapped` must not repeat a
     * field name already covered by a Placeholder.
     */
    fun testFieldCoveredByAConstructorPlaceholderIsNotAlsoListedInUnmapped() {
        val source = classOf(myFixture.addFileToProject("S3b.kt", "class S3b(val a: String)"))
        val target = classOf(myFixture.addFileToProject("T3b.kt", "data class T3b(val a: String, val b: Int)"))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertTrue(plan.constructorArgs.any { it is ConstructorArg.Placeholder && it.paramName == "b" })
        assertTrue("'b' should only appear as a constructor-arg TODO, not also in plan.unmapped", plan.unmapped.none { it.targetField.name == "b" })
    }

    fun testConstructorParameterWithNoSourceFieldBecomesAPlaceholder() {
        val source = classOf(myFixture.addFileToProject("S3.kt", "class S3(val a: String)"))
        val target = classOf(myFixture.addFileToProject("T3.kt", "data class T3(val a: String, val b: Int)"))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertEquals(ConstructionStrategy.CONSTRUCTOR, plan.construction)
        assertEquals(2, plan.constructorArgs.size)
        assertTrue(plan.constructorArgs[0] is ConstructorArg.Mapped)
        assertTrue("second constructor param has no matching source field, expected a Placeholder", plan.constructorArgs[1] is ConstructorArg.Placeholder)
    }

    fun testFieldNotInConstructorIsChainedAsALeftoverSetterCall() {
        val source = classOf(myFixture.addFileToProject(
            "S4.kt",
            """
            class S4(val a: String, val extra: String)
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "T4.kt",
            """
            class T4(val a: String) {
                var extra: String = ""
            }
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertEquals(ConstructionStrategy.CONSTRUCTOR, plan.construction)
        val extraMapped = plan.mapped.first { it.targetField.name == "extra" }
        assertTrue(extraMapped.strategy is AssignmentStrategy.Setter)
    }

    fun testLombokStyleBuilderClassIsPreferredOverSetters() {
        val source = classOf(myFixture.addFileToProject(
            "S5.java",
            """
            public class S5 {
                private String label;
                public String getLabel() { return label; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "T5.java",
            """
            @lombok.Builder
            public class T5 {
                private final String label;
                private T5(String label) { this.label = label; }
                public static T5Builder builder() { return new T5Builder(); }

                public static class T5Builder {
                    private String label;
                    public T5Builder label(String label) { this.label = label; return this; }
                    public T5 build() { return new T5(label); }
                }
            }
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertEquals(ConstructionStrategy.BUILDER, plan.construction)
        assertEquals(1, plan.mapped.size)
        assertTrue(plan.mapped.first().strategy is AssignmentStrategy.BuilderCall)
    }

    fun testBuilderAnnotationWithoutAVisibleGeneratedBuilderClassFallsBackGracefully() {
        // Simulates the Lombok IDE plugin NOT being installed: the
        // annotation is real source text (always visible), but its
        // generated inner class is not -- this plugin must not crash or
        // silently produce an all-unmapped plan when a plainer strategy
        // (here: no-args constructor + setters) is actually usable.
        val source = classOf(myFixture.addFileToProject(
            "S6.java",
            """
            public class S6 {
                private String label;
                public String getLabel() { return label; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "T6.java",
            """
            @lombok.Builder
            public class T6 {
                private String label;
                public T6() {}
                public String getLabel() { return label; }
                public void setLabel(String label) { this.label = label; }
            }
            """.trimIndent(),
        ))

        val plan = TargetAssigner.assign(source, target, FieldMatcher.match(source, target))

        assertEquals(ConstructionStrategy.NO_ARGS_THEN_SETTERS, plan.construction)
        assertEquals(1, plan.mapped.size)
    }
}
