package dev.gaphunter.beancopycompanion.match

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import dev.gaphunter.beancopycompanion.model.SourceAccess
import dev.gaphunter.beancopycompanion.model.UnmappedField

/**
 * One target field this plugin can safely read a value for -- name AND
 * type both resolved, [access] already carries the real [PsiMethod]/
 * [PsiField] to read from (never a guessed name string). Whether/how it
 * gets ASSIGNED on the target (setter, constructor param, builder call)
 * is [dev.gaphunter.beancopycompanion.match.TargetAssigner]'s job, kept
 * separate on purpose -- this class only answers "can the value be read
 * at all", not "how does it get written".
 */
data class FieldCandidate(val targetField: PsiField, val access: SourceAccess)

data class FieldMatchResult(val candidates: List<FieldCandidate>, val unmatched: List<UnmappedField>)

/**
 * Matches target fields to source fields by exact name + assignment-
 * compatible type, per CONSTITUTION.md's evidence for this plugin (real
 * competitor review: "不支持kotlin" -- no Kotlin support, and a real
 * `PsiMethod.getProject()` NPE crash from generating against a field
 * that couldn't actually be read). Every target field either becomes a
 * [FieldCandidate] with a real, verified way to read the value, or an
 * [UnmappedField] with a specific, human-readable reason -- there is no
 * third "guess and hope" outcome.
 */
object FieldMatcher {

    fun match(sourceClass: PsiClass, targetClass: PsiClass): FieldMatchResult {
        val sourceFields = relevantFields(sourceClass)
        val candidates = mutableListOf<FieldCandidate>()
        val unmatched = mutableListOf<UnmappedField>()

        for (targetField in relevantFields(targetClass)) {
            val sourceField = sourceFields.firstOrNull { it.name == targetField.name }
            if (sourceField == null) {
                unmatched += UnmappedField(targetField, "no field named '${targetField.name}' found on ${sourceClass.name ?: "the source class"}")
                continue
            }
            if (!isAssignable(sourceField.type, targetField.type)) {
                unmatched += UnmappedField(
                    targetField,
                    "type mismatch: ${sourceField.type.presentableText} on ${sourceClass.name} -> ${targetField.type.presentableText} on ${targetClass.name}",
                )
                continue
            }
            val access = readAccessFor(sourceField, sourceClass)
            if (access == null) {
                unmatched += UnmappedField(targetField, "'${targetField.name}' has no public getter and no public field on ${sourceClass.name ?: "the source class"}")
                continue
            }
            candidates += FieldCandidate(targetField, access)
        }
        return FieldMatchResult(candidates, unmatched)
    }

    /**
     * Own, non-static fields only -- deliberately mirrors
     * [dev.gaphunter.testscaffoldcompanion.generate.PublicMethodCollector]'s
     * choice of [PsiClass.getFields] over `getAllFields()`: pulling in
     * inherited fields from unrelated superclasses (or `java.lang.Object`,
     * which has none, but library base classes might) would be noise the
     * user didn't ask to map, not signal.
     */
    private fun relevantFields(psiClass: PsiClass): List<PsiField> =
        psiClass.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) }

    /**
     * Tries a real getter method first (works for a Java field with a
     * conventional getter, AND for a Kotlin `val`/`var` property, whose
     * light class always exposes a synthesized `getFoo()`/`isFoo()`
     * bytecode method -- see [dev.gaphunter.beancopycompanion.generate.CopierWriter]
     * for why the RENDERED call syntax still has to branch on the source
     * class's language even though resolution here doesn't). Falls back
     * to direct field access only when the field itself is public --
     * that never happens for a real Kotlin property (always backed by a
     * private field), so this fallback is a Java-only case in practice.
     */
    private fun readAccessFor(field: PsiField, ownerClass: PsiClass): SourceAccess? {
        findGetter(ownerClass, field)?.let { return SourceAccess.Getter(it, field.name) }
        if (field.hasModifierProperty(PsiModifier.PUBLIC)) return SourceAccess.PublicField(field)
        return null
    }

    fun findGetter(ownerClass: PsiClass, field: PsiField): PsiMethod? {
        val candidateNames = getterCandidateNames(field)
        return ownerClass.methods.firstOrNull { m ->
            m.name in candidateNames &&
                m.parameterList.parametersCount == 0 &&
                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                !m.hasModifierProperty(PsiModifier.STATIC)
        }
    }

    fun findSetter(ownerClass: PsiClass, field: PsiField): PsiMethod? {
        val candidateNames = setterCandidateNames(field)
        return ownerClass.methods.firstOrNull { m ->
            m.name in candidateNames &&
                m.parameterList.parametersCount == 1 &&
                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                !m.hasModifierProperty(PsiModifier.STATIC) &&
                isAssignable(field.type, m.parameterList.parameters[0].type)
        }
    }

    /**
     * Tries both Java Beans convention (`get`/`is` + capitalized name)
     * AND Kotlin's own convention for a property whose name already
     * starts with `is` (e.g. `var isActive: Boolean` compiles to
     * `isActive()`, NOT `getIsActive()`/`isIsActive()`) -- rather than
     * assume one language's naming rule, every plausible real method
     * name is checked and whichever one the class actually declares
     * wins. Never invents a name that isn't independently confirmed to
     * exist as a real callable method (see [findGetter]/[findSetter]).
     */
    private fun getterCandidateNames(field: PsiField): List<String> {
        val capitalized = field.name.replaceFirstChar { it.uppercaseChar() }
        val names = mutableListOf("get$capitalized")
        if (isBooleanType(field.type)) {
            names += "is$capitalized"
            if (startsWithIs(field.name)) names += field.name
        }
        return names
    }

    private fun setterCandidateNames(field: PsiField): List<String> {
        val capitalized = field.name.replaceFirstChar { it.uppercaseChar() }
        val names = mutableListOf("set$capitalized")
        if (isBooleanType(field.type) && startsWithIs(field.name)) {
            names += "set" + field.name.substring(2) // Kotlin: `var isActive` -> setter `setActive`
        }
        return names
    }

    private fun startsWithIs(name: String): Boolean =
        name.length > 2 && name.startsWith("is") && name[2].isUpperCase()

    private fun isBooleanType(type: PsiType): Boolean =
        type == PsiTypes.booleanType() || type.canonicalText == "java.lang.Boolean"

    private val PRIMITIVE_TO_BOXED_SIMPLE_NAME = mapOf(
        "boolean" to "Boolean",
        "byte" to "Byte",
        "char" to "Character",
        "short" to "Short",
        "int" to "Integer",
        "long" to "Long",
        "float" to "Float",
        "double" to "Double",
    )

    /**
     * Real assignability (subtype/interface included, via the platform's
     * own [PsiType.isAssignableFrom]) OR primitive<->boxed equivalence,
     * which [PsiType.isAssignableFrom] does NOT consider assignable on
     * its own (an `int` field and an `Integer` field are a completely
     * legitimate, common copy pair -- e.g. a Java entity's `int` id
     * copied into a Kotlin DTO's boxed `Int?`/`Long?`).
     *
     * The boxed side is compared by SIMPLE name, never [PsiType.getCanonicalText]
     * in full -- confirmed live (a real test failure, not guessed) that
     * `canonicalText` does NOT resolve to the fully-qualified name in a
     * lightweight test fixture without the full JDK indexed (prints
     * `"Integer"`, not `"java.lang.Integer"`), the exact same gotcha
     * already documented for Test Scaffold Companion's assertion
     * inference in `INTELLIJ_PLATFORM_KNOWLEDGE.md`. Scoped to only
     * fire when exactly one side is a genuine [PsiPrimitiveType]
     * (a structural check, not a string comparison) so this never
     * accidentally treats two unrelated reference types that merely
     * share a simple name as equivalent.
     */
    fun isAssignable(sourceType: PsiType, targetType: PsiType): Boolean {
        if (targetType.isAssignableFrom(sourceType)) return true
        val sourceIsPrimitive = sourceType is PsiPrimitiveType
        val targetIsPrimitive = targetType is PsiPrimitiveType
        if (sourceIsPrimitive == targetIsPrimitive) return false // both primitive (a real mismatch, e.g. int vs boolean) or both reference (not a boxing case at all)

        val primitiveSide = if (sourceIsPrimitive) sourceType else targetType
        val boxedSide = if (sourceIsPrimitive) targetType else sourceType
        val expectedBoxedSimpleName = PRIMITIVE_TO_BOXED_SIMPLE_NAME[primitiveSide.canonicalText] ?: return false
        return simpleName(boxedSide) == expectedBoxedSimpleName
    }

    private fun simpleName(type: PsiType): String = type.canonicalText.substringAfterLast('.')
}
