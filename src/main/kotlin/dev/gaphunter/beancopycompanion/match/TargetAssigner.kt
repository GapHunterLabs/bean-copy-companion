package dev.gaphunter.beancopycompanion.match

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import dev.gaphunter.beancopycompanion.model.AssignmentStrategy
import dev.gaphunter.beancopycompanion.model.ConstructionStrategy
import dev.gaphunter.beancopycompanion.model.ConstructorArg
import dev.gaphunter.beancopycompanion.model.CopyPlan
import dev.gaphunter.beancopycompanion.model.MappedField
import dev.gaphunter.beancopycompanion.model.UnmappedField

private const val LOMBOK_BUILDER_FQN = "lombok.Builder"

/**
 * Decides HOW the target instance actually gets built, given the fields
 * [FieldMatcher] already confirmed are readable. Priority order is
 * deliberate and mirrors real-world class shapes in this exact order:
 *
 * 1. **Lombok `@Builder`** -- if present, always preferred: it's an
 *    explicit, intentional API the class author designed for exactly
 *    this purpose, and skipping it in favor of raw setters would ignore
 *    the class's own contract (e.g. a `@Builder` class with `final`
 *    fields has no setters to fall back to at all).
 * 2. **No-args constructor + setters** -- the common mutable-bean shape.
 * 3. **Constructor call** -- Kotlin `data class`, Java `record`, or any
 *    class whose only constructor takes arguments; any mapped field left
 *    over after the constructor call (not one of its parameters) is
 *    chained as a setter call afterward, same discipline as the free
 *    competitor's own later changelog entries ("no args first") but
 *    reached deliberately instead of patched in after user bug reports.
 *
 * Never throws on an unsupported shape -- worst case, every field ends
 * up in [CopyPlan.unmapped] with a specific reason, and the caller
 * decides whether an all-unmapped plan is even worth writing.
 */
object TargetAssigner {

    fun assign(sourceClass: PsiClass, targetClass: PsiClass, matchResult: FieldMatchResult): CopyPlan {
        assignViaBuilder(targetClass, matchResult)?.let { return it.toPlan(sourceClass, targetClass, ConstructionStrategy.BUILDER) }

        val hasUsableNoArgsConstructor = targetClass.constructors.isEmpty() ||
            targetClass.constructors.any { it.parameterList.parametersCount == 0 && it.hasModifierProperty(PsiModifier.PUBLIC) }
        if (hasUsableNoArgsConstructor) {
            return assignViaSetters(matchResult).toPlan(sourceClass, targetClass, ConstructionStrategy.NO_ARGS_THEN_SETTERS)
        }

        return assignViaConstructor(targetClass, matchResult, sourceClass)
    }

    private data class Assignment(val mapped: List<MappedField>, val unmapped: List<UnmappedField>) {
        fun toPlan(sourceClass: PsiClass, targetClass: PsiClass, construction: ConstructionStrategy) =
            CopyPlan(sourceClass, targetClass, construction, emptyList(), mapped, unmapped)
    }

    private fun assignViaSetters(matchResult: FieldMatchResult): Assignment {
        val mapped = mutableListOf<MappedField>()
        val extraUnmapped = mutableListOf<UnmappedField>()
        for (candidate in matchResult.candidates) {
            val setter = FieldMatcher.findSetter(candidate.targetField.containingClass ?: continue, candidate.targetField)
            if (setter != null) {
                mapped += MappedField(candidate.targetField, candidate.access, AssignmentStrategy.Setter(setter))
            } else {
                extraUnmapped += UnmappedField(candidate.targetField, "has a no-args constructor but no public setter for '${candidate.targetField.name}'")
            }
        }
        return Assignment(mapped, matchResult.unmatched + extraUnmapped)
    }

    /**
     * Lombok's IDE plugin has to actually be installed for a `@Builder`
     * class's generated `XBuilder` inner class/methods to be visible in
     * the PSI at all -- the annotation itself is always visible (it's
     * real source text), but its *effects* are not, without that plugin
     * processing it. Detected here by trying to resolve the generated
     * shape and simply falling through to the next strategy (never an
     * error) if it isn't there -- same "degrade, don't crash" discipline
     * as everywhere else in this plugin.
     */
    private fun assignViaBuilder(targetClass: PsiClass, matchResult: FieldMatchResult): Assignment? {
        if (!targetClass.hasAnnotation(LOMBOK_BUILDER_FQN)) return null
        val builderClass = targetClass.innerClasses.firstOrNull { it.name == "${targetClass.name}Builder" } ?: return null
        val hasBuildMethod = builderClass.methods.any { it.name == "build" && it.parameterList.parametersCount == 0 }
        if (!hasBuildMethod) return null

        val mapped = mutableListOf<MappedField>()
        val extraUnmapped = mutableListOf<UnmappedField>()
        for (candidate in matchResult.candidates) {
            val builderMethod = findBuilderMethod(builderClass, candidate.targetField.name, candidate.targetField.type)
            if (builderMethod != null) {
                mapped += MappedField(candidate.targetField, candidate.access, AssignmentStrategy.BuilderCall(builderMethod))
            } else {
                extraUnmapped += UnmappedField(candidate.targetField, "@Builder present but no builder method '${candidate.targetField.name}(..)' found on ${builderClass.name}")
            }
        }
        // A @Builder class with zero usable builder methods for any candidate field isn't
        // actually usable as a builder plan -- fall through to the next strategy instead
        // of handing back a plan that's 100% unmapped when a plainer strategy might do better.
        if (mapped.isEmpty() && matchResult.candidates.isNotEmpty()) return null
        return Assignment(mapped, matchResult.unmatched + extraUnmapped)
    }

    private fun findBuilderMethod(builderClass: PsiClass, fieldName: String, fieldType: com.intellij.psi.PsiType): PsiMethod? =
        builderClass.methods.firstOrNull { m ->
            m.name == fieldName &&
                m.parameterList.parametersCount == 1 &&
                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                FieldMatcher.isAssignable(fieldType, m.parameterList.parameters[0].type)
        }

    /**
     * Picks the constructor with the most parameters (the "canonical"
     * one for a Kotlin `data class`/Java `record`, and the most-capable
     * one for any other args-only class) and maps each parameter to a
     * candidate field by exact name. Any candidate field NOT consumed by
     * the constructor is chained as a setter call afterward if a setter
     * exists (covers a Kotlin class with both constructor-`val`s and
     * extra mutable `var`s declared in the body).
     */
    private fun assignViaConstructor(targetClass: PsiClass, matchResult: FieldMatchResult, sourceClass: PsiClass): CopyPlan {
        val constructor = targetClass.constructors
            .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
            .maxByOrNull { it.parameterList.parametersCount }

        if (constructor == null) {
            // No public constructor at all -- nothing this plugin can safely call.
            val unmapped = matchResult.candidates.map { UnmappedField(it.targetField, "${targetClass.name} has no public constructor") } +
                matchResult.unmatched
            return CopyPlan(sourceClass, targetClass, ConstructionStrategy.CONSTRUCTOR, emptyList(), emptyList(), unmapped)
        }

        val consumedFieldNames = mutableSetOf<String>()
        val constructorArgs = constructor.parameterList.parameters.map { param ->
            val candidate = matchResult.candidates.firstOrNull { it.targetField.name == param.name && FieldMatcher.isAssignable(it.targetField.type, param.type) }
            if (candidate != null) {
                consumedFieldNames += candidate.targetField.name
                ConstructorArg.Mapped(MappedField(candidate.targetField, candidate.access, AssignmentStrategy.ConstructorParam))
            } else {
                ConstructorArg.Placeholder(param.name ?: "arg", param.type)
            }
        }

        val leftoverCandidates = matchResult.candidates.filter { it.targetField.name !in consumedFieldNames }
        val mapped = constructorArgs.filterIsInstance<ConstructorArg.Mapped>().map { it.mappedField }.toMutableList()
        val extraUnmapped = mutableListOf<UnmappedField>()
        for (candidate in leftoverCandidates) {
            val setter = FieldMatcher.findSetter(targetClass, candidate.targetField)
            if (setter != null) {
                mapped += MappedField(candidate.targetField, candidate.access, AssignmentStrategy.Setter(setter))
            } else {
                extraUnmapped += UnmappedField(candidate.targetField, "not a constructor parameter and no public setter for '${candidate.targetField.name}'")
            }
        }

        // A field that couldn't be matched at all (matchResult.unmatched)
        // AND is also a constructor parameter already shows up as a
        // ConstructorArg.Placeholder, with its own inline TODO right at
        // the call site -- repeating it again in the standalone TODO
        // list below would just say the same thing twice. Real
        // duplication found live (runIde, 2026-08-13): the demo's
        // OrderDto(customer: String) case showed 'customer' flagged
        // both inline and at the end.
        val placeholderParamNames = constructorArgs.filterIsInstance<ConstructorArg.Placeholder>().map { it.paramName }.toSet()
        val dedupedUnmatched = matchResult.unmatched.filter { it.targetField.name !in placeholderParamNames }

        return CopyPlan(sourceClass, targetClass, ConstructionStrategy.CONSTRUCTOR, constructorArgs, mapped, dedupedUnmatched + extraUnmapped)
    }
}
