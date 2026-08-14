package dev.gaphunter.beancopycompanion.generate

import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import dev.gaphunter.beancopycompanion.model.AssignmentStrategy
import dev.gaphunter.beancopycompanion.model.ConstructionStrategy
import dev.gaphunter.beancopycompanion.model.ConstructorArg
import dev.gaphunter.beancopycompanion.model.CopyPlan
import dev.gaphunter.beancopycompanion.model.MappedField
import dev.gaphunter.beancopycompanion.model.SourceAccess
import dev.gaphunter.beancopycompanion.util.LanguageDetector

data class RenderedCopier(val fileName: String, val text: String)

/**
 * Renders the actual copier source text -- pure string generation, no
 * PSI mutation, no I/O, same separation of concerns already proven in
 * this catalog between a "what should the text look like" writer and
 * the caller that decides whether/where to persist it (see
 * TestSkeletonWriter/InMemoryValidator in test-scaffold-companion).
 *
 * The generated LANGUAGE always matches the TARGET class's language
 * (Java target -> Java file, Kotlin target -> Kotlin file) -- generating
 * a `.kt` copier into a pure-Java project with no Kotlin configured
 * would hand the user code that can't even compile, exactly the class
 * of complaint this plugin exists to not repeat.
 *
 * The trickiest correctness point, and the reason [SourceAccess]/
 * [AssignmentStrategy] never carry a pre-baked expression string: a
 * Kotlin property has no callable `getFoo()`/`setFoo(v)` symbol from
 * Kotlin-to-Kotlin call sites -- only from Java, or from Kotlin calling
 * into a *Java* class (Kotlin exposes a Java getter as a synthetic
 * property, but does not expose a Kotlin property's own synthesized
 * accessor back to Kotlin source). So the rendered syntax depends on
 * BOTH ends of every read/write, not just the generated file's own
 * language:
 *
 * | generated | other side | syntax used |
 * |---|---|---|
 * | Kotlin | Kotlin | property (`x.foo`, `x.foo = v`) |
 * | Kotlin | Java | method call (`x.getFoo()`, `x.setFoo(v)`) |
 * | Java | Kotlin or Java | method call (always -- Java has no property syntax) |
 */
object CopierWriter {

    fun render(plan: CopyPlan, packageName: String?): RenderedCopier {
        val targetIsKotlin = LanguageDetector.isKotlinClass(plan.targetClass)
        val sourceIsKotlin = LanguageDetector.isKotlinClass(plan.sourceClass)
        val sourceName = plan.sourceClass.name ?: "Source"
        val targetName = plan.targetClass.name ?: "Target"
        val baseName = "${sourceName}To${targetName}Copier"

        val text = if (targetIsKotlin) {
            renderKotlin(plan, baseName, packageName, sourceIsKotlin, sourceName, targetName)
        } else {
            renderJava(plan, baseName, packageName, sourceIsKotlin, sourceName, targetName)
        }
        return RenderedCopier(baseName, text)
    }

    // ---------------------------------------------------------------- Java

    private fun renderJava(plan: CopyPlan, className: String, packageName: String?, sourceIsKotlin: Boolean, sourceName: String, targetName: String): String {
        val receiver = "source"
        val targetVar = "target"
        val body = StringBuilder()

        when (plan.construction) {
            ConstructionStrategy.BUILDER -> {
                body.append("        $targetName $targetVar = $targetName.builder()")
                for (mapped in plan.mapped) {
                    val method = (mapped.strategy as AssignmentStrategy.BuilderCall).builderMethod
                    body.append("\n            .${method.name}(${readExpr(mapped.access, sourceIsKotlin, generateKotlin = false, receiver = receiver)})")
                }
                body.append("\n            .build();\n")
            }
            ConstructionStrategy.NO_ARGS_THEN_SETTERS -> {
                body.append("        $targetName $targetVar = new $targetName();\n")
                for (mapped in plan.mapped) {
                    body.append(renderSetterLine(mapped, targetIsKotlin = false, sourceIsKotlin = sourceIsKotlin, generateKotlin = false, readExprText = readExpr(mapped.access, sourceIsKotlin, generateKotlin = false, receiver = receiver), targetVar = targetVar))
                    body.append(";\n")
                }
            }
            ConstructionStrategy.CONSTRUCTOR -> {
                if (plan.constructorArgs.isEmpty()) {
                    body.append("        // TODO(bean-copy): $targetName has no usable public constructor -- nothing generated\n")
                    body.append("        $targetName $targetVar = null;\n")
                } else {
                    body.append("        $targetName $targetVar = new $targetName(\n")
                    body.append(renderConstructorArgs(plan.constructorArgs, sourceIsKotlin, generateKotlin = false, receiver = receiver))
                    body.append("\n        );\n")
                    val consumed = plan.constructorArgs.filterIsInstance<ConstructorArg.Mapped>().map { it.mappedField }.toSet()
                    for (mapped in plan.mapped) {
                        if (mapped in consumed) continue
                        body.append(renderSetterLine(mapped, targetIsKotlin = false, sourceIsKotlin = sourceIsKotlin, generateKotlin = false, readExprText = readExpr(mapped.access, sourceIsKotlin, generateKotlin = false, receiver = receiver), targetVar = targetVar))
                        body.append(";\n")
                    }
                }
            }
        }

        for (unmapped in plan.unmapped) {
            body.append("        // TODO(bean-copy): '${unmapped.targetField.name}' -- ${unmapped.reason}\n")
        }
        body.append("        return $targetVar;\n")

        return buildString {
            if (!packageName.isNullOrEmpty()) {
                appendLine("package $packageName;")
                appendLine()
            }
            appendLine("/**")
            appendLine(" * Generated by Bean Copy Companion from $sourceName to $targetName --")
            appendLine(" * ${plan.mapped.size} field(s) mapped, ${plan.unmapped.size} left as TODOs below.")
            appendLine(" * Do not hand-edit; regenerate instead.")
            appendLine(" */")
            appendLine("public final class $className {")
            appendLine()
            appendLine("    private $className() {}")
            appendLine()
            appendLine("    public static $targetName copy($sourceName $receiver) {")
            append(body)
            appendLine("    }")
            appendLine("}")
        }
    }

    // -------------------------------------------------------------- Kotlin

    private fun renderKotlin(plan: CopyPlan, fileBaseName: String, packageName: String?, sourceIsKotlin: Boolean, sourceName: String, targetName: String): String {
        val receiver = "this"
        val targetVar = "target"
        val functionName = "to$targetName"
        val body = StringBuilder()

        when (plan.construction) {
            ConstructionStrategy.BUILDER -> {
                body.append("    val $targetVar = $targetName.builder()")
                for (mapped in plan.mapped) {
                    val method = (mapped.strategy as AssignmentStrategy.BuilderCall).builderMethod
                    body.append("\n        .${method.name}(${readExpr(mapped.access, sourceIsKotlin, generateKotlin = true, receiver = receiver)})")
                }
                body.append("\n        .build()\n")
            }
            ConstructionStrategy.NO_ARGS_THEN_SETTERS -> {
                body.append("    val $targetVar = $targetName()\n")
                for (mapped in plan.mapped) {
                    body.append(renderSetterLine(mapped, targetIsKotlin = true, sourceIsKotlin = sourceIsKotlin, generateKotlin = true, readExprText = readExpr(mapped.access, sourceIsKotlin, generateKotlin = true, receiver = receiver), targetVar = targetVar))
                    body.append("\n")
                }
            }
            ConstructionStrategy.CONSTRUCTOR -> {
                if (plan.constructorArgs.isEmpty()) {
                    body.append("    // TODO(bean-copy): $targetName has no usable public constructor -- nothing generated\n")
                    body.append("    val $targetVar: $targetName? = null\n")
                } else {
                    body.append("    val $targetVar = $targetName(\n")
                    body.append(renderConstructorArgs(plan.constructorArgs, sourceIsKotlin, generateKotlin = true, receiver = receiver))
                    body.append("\n    )\n")
                    val consumed = plan.constructorArgs.filterIsInstance<ConstructorArg.Mapped>().map { it.mappedField }.toSet()
                    for (mapped in plan.mapped) {
                        if (mapped in consumed) continue
                        body.append(renderSetterLine(mapped, targetIsKotlin = true, sourceIsKotlin = sourceIsKotlin, generateKotlin = true, readExprText = readExpr(mapped.access, sourceIsKotlin, generateKotlin = true, receiver = receiver), targetVar = targetVar))
                        body.append("\n")
                    }
                }
            }
        }

        for (unmapped in plan.unmapped) {
            body.append("    // TODO(bean-copy): '${unmapped.targetField.name}' -- ${unmapped.reason}\n")
        }
        body.append("    return $targetVar\n")

        return buildString {
            if (!packageName.isNullOrEmpty()) {
                appendLine("package $packageName")
                appendLine()
            }
            appendLine("/**")
            appendLine(" * Generated by Bean Copy Companion from $sourceName to $targetName --")
            appendLine(" * ${plan.mapped.size} field(s) mapped, ${plan.unmapped.size} left as TODOs below.")
            appendLine(" * Do not hand-edit; regenerate instead.")
            appendLine(" */")
            appendLine("fun $sourceName.$functionName(): $targetName {")
            append(body)
            appendLine("}")
        }
    }

    // -------------------------------------------------------- shared parts

    private fun readExpr(access: SourceAccess, sourceIsKotlin: Boolean, generateKotlin: Boolean, receiver: String): String =
        when (access) {
            is SourceAccess.Getter ->
                if (generateKotlin && sourceIsKotlin) "$receiver.${access.fieldName}" else "$receiver.${access.method.name}()"
            is SourceAccess.PublicField -> "$receiver.${access.field.name}"
        }

    private fun renderSetterLine(mapped: MappedField, targetIsKotlin: Boolean, sourceIsKotlin: Boolean, generateKotlin: Boolean, readExprText: String, targetVar: String): String {
        val setterMethod = (mapped.strategy as AssignmentStrategy.Setter).method
        return if (generateKotlin && targetIsKotlin) {
            "    $targetVar.${mapped.targetField.name} = $readExprText"
        } else if (generateKotlin) {
            "    $targetVar.${setterMethod.name}($readExprText)"
        } else {
            "        $targetVar.${setterMethod.name}($readExprText);"
        }
    }

    private fun renderConstructorArgs(args: List<ConstructorArg>, sourceIsKotlin: Boolean, generateKotlin: Boolean, receiver: String): String {
        val indent = if (generateKotlin) "        " else "            "
        return args.joinToString(",\n") { arg ->
            when (arg) {
                is ConstructorArg.Mapped -> indent + readExpr(arg.mappedField.access, sourceIsKotlin, generateKotlin, receiver)
                is ConstructorArg.Placeholder -> {
                    val comment = "// TODO(bean-copy): no source value for constructor param '${arg.paramName}' (${arg.paramType.presentableText})"
                    indent + defaultValueFor(arg.paramType) + " $comment"
                }
            }
        }
    }

    /**
     * A syntactically valid placeholder value so the generated call
     * compiles at the SYNTAX level even for a parameter with no source
     * data -- same "honest placeholder, never invented real-looking
     * data" philosophy as Test Scaffold Companion's `defaultValueFor`.
     * Only a Layer 1 (syntax) guarantee, same documented scope as
     * [InMemoryValidator]: a `null` placed against a non-nullable Kotlin
     * type is syntactically valid but will fail Kotlin's own type
     * checker -- the adjacent TODO comment is what makes that
     * unmissable, not a claim that this always compiles as-is.
     */
    private fun defaultValueFor(type: PsiType): String = when {
        type == PsiTypes.booleanType() -> "false"
        type is PsiPrimitiveType -> "0"
        type.canonicalText == "java.lang.String" -> "\"\""
        else -> "null"
    }
}
