package dev.gaphunter.beancopycompanion.model

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType

/**
 * How a source value is actually read. Kept as structured data (never a
 * pre-baked string) because the correct syntax depends on BOTH the
 * generated file's language AND the source class's language -- see
 * [dev.gaphunter.beancopycompanion.generate.CopierWriter] for why: a
 * Kotlin property has no callable `getFoo()` symbol from Kotlin-to-Kotlin
 * call sites (only from Java, or from Kotlin calling into a *Java*
 * class), so the same [Getter] here renders as `source.getFoo()` in one
 * context and `source.foo` in another.
 */
sealed class SourceAccess {
    /**
     * A real getter method exists on the source class (works from Java
     * always, and from Kotlin when the source itself is Java). [fieldName]
     * is carried separately from [method] because Kotlin-to-Kotlin
     * property-syntax rendering needs the ORIGINAL property name, not
     * something re-derived (lossily) from the getter method's name --
     * e.g. a property named `isActive` has getter method `isActive()`
     * too (no "get"/double "is"), so stripping a prefix from the method
     * name to recover the property name would need to already know
     * which convention applies -- [fieldName] sidesteps that entirely.
     */
    data class Getter(val method: PsiMethod, val fieldName: String) : SourceAccess()

    /** No getter, but the field itself is public (Java-only case -- Kotlin properties are never plain public fields at the bytecode level). */
    data class PublicField(val field: PsiField) : SourceAccess()
}

/** How a mapped target field's value gets assigned once read from the source. */
sealed class AssignmentStrategy {
    /** `target.setX(value)` (or `target.x = value` in Kotlin-to-Kotlin, see [dev.gaphunter.beancopycompanion.generate.CopierWriter]). */
    data class Setter(val method: PsiMethod) : AssignmentStrategy()
    /** Passed positionally as one argument of the constructor call used to build the target instance. */
    data object ConstructorParam : AssignmentStrategy()
    /** Lombok `@Builder` fluent call `.x(value)` on the generated builder. */
    data class BuilderCall(val builderMethod: PsiMethod) : AssignmentStrategy()
}

/** One target field this plugin knows how to fill in, and exactly how. */
data class MappedField(
    val targetField: PsiField,
    val access: SourceAccess,
    val strategy: AssignmentStrategy,
)

/** One target field this plugin could NOT fill in -- rendered as an honest TODO, never silently dropped or crashed on. */
data class UnmappedField(
    val targetField: PsiField,
    val reason: String,
)

/** Which top-level assembly shape the whole target instance is built with. */
enum class ConstructionStrategy {
    /** Lombok `@Builder` -- `Target.builder().x(..).y(..).build()`. */
    BUILDER,

    /** A public no-args constructor exists -- `new Target()` (or `Target()`) then setter calls. */
    NO_ARGS_THEN_SETTERS,

    /** No usable no-args constructor (Kotlin data class, Java record, all-args-only class) -- constructor call with any leftover mapped fields chained as setters afterward. */
    CONSTRUCTOR,
}

/** One positional slot of the constructor call used by [ConstructionStrategy.CONSTRUCTOR]. */
sealed class ConstructorArg {
    data class Mapped(val mappedField: MappedField) : ConstructorArg()

    /** No mapped field covers this parameter -- still needs *some* syntactically valid expression so the call compiles; rendered with a TODO comment flagging it, same "honest placeholder" philosophy as Test Scaffold Companion's default argument values. */
    data class Placeholder(val paramName: String, val paramType: PsiType) : ConstructorArg()
}

data class CopyPlan(
    val sourceClass: PsiClass,
    val targetClass: PsiClass,
    val construction: ConstructionStrategy,
    /** Only non-empty for [ConstructionStrategy.CONSTRUCTOR]: the constructor's own parameters, in declaration order. */
    val constructorArgs: List<ConstructorArg> = emptyList(),
    val mapped: List<MappedField>,
    val unmapped: List<UnmappedField>,
)
