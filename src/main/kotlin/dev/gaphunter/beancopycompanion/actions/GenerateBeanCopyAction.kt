package dev.gaphunter.beancopycompanion.actions

import com.intellij.lang.java.JavaLanguage
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import dev.gaphunter.beancopycompanion.generate.CopierWriter
import dev.gaphunter.beancopycompanion.generate.InMemoryValidator
import dev.gaphunter.beancopycompanion.match.FieldMatcher
import dev.gaphunter.beancopycompanion.match.TargetAssigner
import dev.gaphunter.beancopycompanion.model.CopyPlan
import dev.gaphunter.beancopycompanion.util.LanguageDetector
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * Project-view context-menu entry point. Requires exactly two files
 * selected, each resolving to one usable top-level class -- see
 * [resolveTwoClasses] for why this plugin doesn't try a single-file +
 * modal-class-picker flow instead.
 *
 * All the real work (PSI walk, field matching, in-memory validation)
 * runs on a background thread via `executeOnPooledThread`, per
 * CONSTITUTION.md section 6 -- only the final `WriteCommandAction` that
 * actually creates the file touches the EDT, and only after validation
 * already passed. Same threading discipline already proven in Test
 * Scaffold Companion's `GenerateTestSkeletonAction` (that pattern was
 * confirmed necessary the hard way, by a real `runIde` crash -- not
 * copied here on faith, but because it's the same class of PSI-heavy
 * work under the same platform rules).
 */
class GenerateBeanCopyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val classes = resolveTwoClasses(e)
        e.presentation.isEnabledAndVisible = project != null && !DumbService.isDumb(project) && classes != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (classA, classB) = resolveTwoClasses(e) ?: return

        val nameA = classA.name ?: "the first class"
        val nameB = classB.name ?: "the second class"
        val choice = Messages.showDialog(
            project,
            "Copy fields in which direction?",
            "Generate Bean Copy Method",
            arrayOf("$nameA → $nameB", "$nameB → $nameA"),
            0,
            Messages.getQuestionIcon(),
        )
        if (choice != 0 && choice != 1) return // cancelled (Escape / closed the dialog)

        val sourceClass = if (choice == 0) classA else classB
        val targetClass = if (choice == 0) classB else classA

        // Everything below touches PSI/the stub index (FieldMatcher,
        // TargetAssigner both call into PsiClass/PsiField/PsiMethod APIs
        // that require a read action even off the EDT) -- see
        // Test Scaffold Companion's GenerateTestSkeletonAction for the
        // real runIde crash that first proved this necessary.
        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val matchResult = FieldMatcher.match(sourceClass, targetClass)
                val plan = TargetAssigner.assign(sourceClass, targetClass, matchResult)

                if (plan.mapped.isEmpty()) {
                    notify(project, "No mappable fields found between ${sourceClass.name} and ${targetClass.name} -- nothing generated.", NotificationType.WARNING)
                    return@runReadAction
                }

                val packageName = (targetClass.containingFile as? PsiClassOwner)?.packageName
                val rendered = CopierWriter.render(plan, packageName)
                val targetIsKotlin = LanguageDetector.isKotlinClass(targetClass)
                val fileName = rendered.fileName + if (targetIsKotlin) ".kt" else ".java"

                val error = InMemoryValidator.findFirstSyntaxError(project, rendered.text, fileName)
                if (error != null) {
                    notify(project, "Generated copier failed its own safety check ($error) -- nothing was written. This is the plugin refusing to hand you broken code, not a bug.", NotificationType.ERROR)
                    return@runReadAction
                }

                ApplicationManager.getApplication().invokeLater {
                    writeToDisk(project, targetClass, fileName, rendered.text, plan)
                }
            }
        }
    }

    private fun writeToDisk(project: Project, targetClass: PsiClass, fileName: String, text: String, plan: CopyPlan) {
        val directory = targetClass.containingFile?.containingDirectory
            ?: return notify(project, "Could not resolve a directory to write the copier into.", NotificationType.ERROR)
        val targetIsKotlin = LanguageDetector.isKotlinClass(targetClass)

        WriteCommandAction.runWriteCommandAction(project, "Generate Bean Copy Method", null, {
            val existing = directory.findFile(fileName)
            if (existing != null) {
                notify(project, "$fileName already exists -- not overwriting. Delete it first if you want to regenerate.", NotificationType.WARNING)
                return@runWriteCommandAction
            }
            val language = if (targetIsKotlin) KotlinLanguage.INSTANCE else JavaLanguage.INSTANCE
            val psiFile: PsiFile = PsiFileFactory.getInstance(project).createFileFromText(fileName, language, text)
            directory.add(psiFile)

            val message = if (plan.unmapped.isEmpty()) {
                "$fileName generated -- all ${plan.mapped.size} field(s) mapped."
            } else {
                "$fileName generated -- ${plan.mapped.size} field(s) mapped, ${plan.unmapped.size} left as TODOs (see the file)."
            }
            notify(project, message, if (plan.unmapped.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
        })
    }

    /**
     * Requires exactly two files selected in the Project view, each
     * resolving to exactly one usable top-level class. Deliberately does
     * NOT try to infer an "other" class from a single-file Editor
     * invocation via a modal `TreeClassChooser` dialog -- v1 keeps the
     * entry point to something fully deterministic: what you selected is
     * what gets used, no separate dialog-driven search flow to get
     * subtly wrong. A same-directory 2-file selection matches how a
     * developer already thinks of "these two DTOs" in the Project tree.
     *
     * Uses [CommonDataKeys.VIRTUAL_FILE_ARRAY] + [PsiManager.findFile],
     * NOT `LangDataKeys.PSI_ELEMENT_ARRAY` -- confirmed live in a real
     * runIde sandbox (2026-08-13) that the Project view's multi-selection
     * DataContext does not populate that key at all (the action stayed
     * permanently disabled, guessed-but-never-verified API usage, exactly
     * the kind of mistake `CLAUDE.md` warns about relying on compile
     * success alone to confirm). `VIRTUAL_FILE_ARRAY` is the more
     * fundamental, universally-populated key for "files selected in a
     * tree" across the whole platform.
     */
    private fun resolveTwoClasses(e: AnActionEvent): Pair<PsiClass, PsiClass>? {
        val project = e.project ?: return null
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return null
        if (files.size != 2) return null
        val psiManager = PsiManager.getInstance(project)
        val classes = files.mapNotNull { vf -> (psiManager.findFile(vf) as? PsiClassOwner)?.classes?.firstOrNull() }
        if (classes.size != 2) return null
        return classes[0] to classes[1]
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Bean Copy Companion")
            .createNotification(message, type)
            .notify(project)
    }
}
