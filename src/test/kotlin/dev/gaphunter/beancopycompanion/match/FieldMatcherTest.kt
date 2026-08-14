package dev.gaphunter.beancopycompanion.match

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.gaphunter.beancopycompanion.model.SourceAccess

class FieldMatcherTest : BasePlatformTestCase() {

    private fun classOf(file: PsiFile): PsiClass = (file as PsiClassOwner).classes.first()

    fun testMatchesJavaGetterByNameAndType() {
        val source = classOf(myFixture.addFileToProject(
            "Source.java",
            """
            public class Source {
                private String name;
                public String getName() { return name; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "Target.java",
            """
            public class Target {
                private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """.trimIndent(),
        ))

        val result = FieldMatcher.match(source, target)

        assertEquals(1, result.candidates.size)
        assertTrue(result.unmatched.isEmpty())
        val access = result.candidates.first().access
        assertTrue(access is SourceAccess.Getter)
        assertEquals("getName", (access as SourceAccess.Getter).method.name)
    }

    fun testFieldWithNoCounterpartOnSourceIsUnmatchedWithReason() {
        val source = classOf(myFixture.addFileToProject("Source2.java", "public class Source2 {}"))
        val target = classOf(myFixture.addFileToProject(
            "Target2.java",
            """
            public class Target2 {
                private String email;
                public String getEmail() { return email; }
            }
            """.trimIndent(),
        ))

        val result = FieldMatcher.match(source, target)

        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.unmatched.size)
        assertTrue(result.unmatched.first().reason.contains("no field named 'email'"))
    }

    fun testIncompatibleTypesAreUnmatchedWithReason() {
        val source = classOf(myFixture.addFileToProject(
            "Source3.java",
            """
            public class Source3 {
                private String age;
                public String getAge() { return age; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "Target3.java",
            """
            public class Target3 {
                private int age;
                public int getAge() { return age; }
                public void setAge(int age) { this.age = age; }
            }
            """.trimIndent(),
        ))

        val result = FieldMatcher.match(source, target)

        assertTrue(result.candidates.isEmpty())
        assertEquals(1, result.unmatched.size)
        assertTrue(result.unmatched.first().reason.contains("type mismatch"))
    }

    fun testPrimitiveAndBoxedEquivalentTypesMatch() {
        val source = classOf(myFixture.addFileToProject(
            "Source4.java",
            """
            public class Source4 {
                private int id;
                public int getId() { return id; }
            }
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "Target4.java",
            """
            public class Target4 {
                private Integer id;
                public Integer getId() { return id; }
                public void setId(Integer id) { this.id = id; }
            }
            """.trimIndent(),
        ))

        val result = FieldMatcher.match(source, target)

        assertEquals("unmatched=" + result.unmatched.map { it.reason }, 1, result.candidates.size)
        assertTrue(result.unmatched.isEmpty())
    }

    fun testReadsFromAKotlinSourceClassViaItsSynthesizedGetter() {
        val source = classOf(myFixture.addFileToProject(
            "Source5.kt",
            """
            class Source5(val label: String)
            """.trimIndent(),
        ))
        val target = classOf(myFixture.addFileToProject(
            "Target5.java",
            """
            public class Target5 {
                private String label;
                public String getLabel() { return label; }
                public void setLabel(String label) { this.label = label; }
            }
            """.trimIndent(),
        ))

        val result = FieldMatcher.match(source, target)

        assertEquals(1, result.candidates.size)
        val access = result.candidates.first().access as SourceAccess.Getter
        assertEquals("getLabel", access.method.name)
        assertEquals("label", access.fieldName)
    }

    fun testResolvesKotlinStyleIsPrefixedBooleanGetterAndSetter() {
        val target = classOf(myFixture.addFileToProject(
            "Target6.kt",
            """
            class Target6 {
                var isActive: Boolean = false
            }
            """.trimIndent(),
        ))
        val activeField = target.fields.first { it.name == "isActive" }

        val getter = FieldMatcher.findGetter(target, activeField)
        val setter = FieldMatcher.findSetter(target, activeField)

        assertNotNull("expected a getter for a Kotlin 'isActive' Boolean property", getter)
        assertEquals("isActive", getter!!.name)
        assertNotNull("expected a Kotlin-convention 'setActive' setter (not 'setIsActive')", setter)
        assertEquals("setActive", setter!!.name)
    }
}
