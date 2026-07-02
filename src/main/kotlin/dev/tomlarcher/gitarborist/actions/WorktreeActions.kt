package dev.tomlarcher.gitarborist.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import dev.tomlarcher.gitarborist.carry.WorktreeCarryOverService
import dev.tomlarcher.gitarborist.git.AddWorktreeRequest
import dev.tomlarcher.gitarborist.git.RemoveWorktreeRequest
import dev.tomlarcher.gitarborist.git.WorktreeCacheService
import dev.tomlarcher.gitarborist.git.WorktreeCacheSnapshot
import dev.tomlarcher.gitarborist.git.WorktreeCommandResult
import dev.tomlarcher.gitarborist.git.WorktreeGitService
import dev.tomlarcher.gitarborist.git.WorktreeInfo
import dev.tomlarcher.gitarborist.git.requiresForceRetry
import dev.tomlarcher.gitarborist.open.WorktreeOpenService
import dev.tomlarcher.gitarborist.settings.GitArboristSettingsResolver
import dev.tomlarcher.gitarborist.ui.CarryOverResultDialog
import dev.tomlarcher.gitarborist.ui.CheckoutRemoteBranchDialog
import dev.tomlarcher.gitarborist.ui.CreateWorktreeDialog
import dev.tomlarcher.gitarborist.ui.RemoveWorktreeDialog
import dev.tomlarcher.gitarborist.ui.WorktreePickerDialog
import dev.tomlarcher.gitarborist.util.Notifications
import dev.tomlarcher.gitarborist.util.PathUtil
import java.nio.file.Path

class OpenWorktreesPanelAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("Git Arborist")?.show()
        project.service<WorktreeCacheService>().refreshAsync()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class CreateWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        loadWorktrees(project, "Loading Git worktrees") { snapshot ->
            val main = snapshot.worktrees.firstOrNull { it.isMain } ?: snapshot.worktrees.firstOrNull()
            val repositoryRoot = main?.repositoryRoot ?: project.basePath?.let(Path::of) ?: return@loadWorktrees
            val settings = GitArboristSettingsResolver.effective(project)
            val dialog = CreateWorktreeDialog(project, repositoryRoot, settings.openAfterCreate, settings.defaultWorktreeDirectory)
            if (dialog.showAndGet()) {
                createWorktree(project, dialog.request(), dialog.shouldOpenAfterCreate)
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class CheckoutRemoteBranchAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        loadMainRoot(project, "Loading Git worktrees") { repositoryRoot ->
            val settings = GitArboristSettingsResolver.effective(project)
            runBackground(
                project = project,
                title = "Loading remote branches",
                work = { project.service<WorktreeGitService>().listRemoteBranches(repositoryRoot) },
            ) { branches ->
                val dialog =
                    CheckoutRemoteBranchDialog(
                        project = project,
                        repositoryRoot = repositoryRoot,
                        initialBranches = branches,
                        openByDefault = settings.openAfterCreate,
                        worktreeDirectory = settings.defaultWorktreeDirectory,
                        fetcher = {
                            val service = project.service<WorktreeGitService>()
                            val fetch = service.fetchRemotes(repositoryRoot)
                            if (!fetch.success) error(fetch.output.ifBlank { "Fetch failed" })
                            service.listRemoteBranches(repositoryRoot)
                        },
                    )
                if (dialog.showAndGet()) createWorktree(project, dialog.request(), dialog.shouldOpenAfterCreate)
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class OpenWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorktree(project, includeMain = false, title = "Open Worktree") { worktree ->
            project.service<WorktreeOpenService>().openWorktreeAsync(worktree)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class ReapplyCarryOverAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorktree(project, includeMain = false, title = "Reapply Carry-over") { worktree ->
            reapplyCarryOver(project, worktree)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** Dynamic submenu listing non-current worktrees; each entry opens that worktree. */
class SwitchToWorktreeActionGroup : ActionGroup() {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val snapshot = project.service<WorktreeCacheService>().current()
        val worktrees = snapshot.worktrees.filterNot { it.isCurrent }
        if (worktrees.isEmpty()) {
            return arrayOf(DisabledAction(if (snapshot.loaded) "No worktrees found" else "Loading worktrees..."))
        }
        return worktrees
            .map { worktree ->
                object : DumbAwareAction(worktree.branch ?: worktree.path.fileName?.toString() ?: worktree.path.toString()) {
                    override fun actionPerformed(e: AnActionEvent) {
                        project.service<WorktreeOpenService>().openWorktreeAsync(worktree)
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                }
            }.toTypedArray()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class RemoveWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorktree(project, includeMain = false, title = "Remove Worktree") { worktree ->
            remove(project, worktree)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class PruneWorktreesAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        loadMainRoot(project, "Loading Git worktrees") { root ->
            if (Messages.showYesNoDialog(project, "Run git worktree prune for $root?", "Prune Worktrees", null) == Messages.YES) {
                runCommand(project, "Pruning Git worktrees") {
                    project.service<WorktreeGitService>().prune(root).also {
                        if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                    }
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class RepairWorktreesAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        loadMainRoot(project, "Loading Git worktrees") { root ->
            runCommand(project, "Repairing Git worktrees") {
                project.service<WorktreeGitService>().repair(root).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class LockWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorktree(project, includeMain = false, title = "Lock Worktree") { worktree ->
            val reason =
                Messages.showInputDialog(project, "Lock reason (optional):", "Lock Worktree", null)
                    ?: return@chooseWorktree
            runCommand(project, "Locking Git worktree") {
                project.service<WorktreeGitService>().lockWorktree(worktree, reason.ifBlank { null }).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class UnlockWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorktree(project, includeMain = false, title = "Unlock Worktree", filter = { it.isLocked }) { worktree ->
            runCommand(project, "Unlocking Git worktree") {
                project.service<WorktreeGitService>().unlockWorktree(worktree).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class MoveWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        chooseWorktree(project, includeMain = false, title = "Move Worktree") { worktree ->
            val input =
                Messages
                    .showInputDialog(project, "New worktree path:", "Move Worktree", null, worktree.path.toString(), null)
                    ?.trim()
                    .orEmpty()
            if (input.isEmpty()) return@chooseWorktree
            val newPath = Path.of(input)
            runCommand(project, "Moving Git worktree") {
                project.service<WorktreeGitService>().move(worktree, newPath).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class ProjectViewOpenWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = openSelected(e)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class ProjectViewReapplyCarryOverAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val worktree = selectedWorktree(e) ?: return
        reapplyCarryOver(project, worktree)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

class ProjectViewRemoveWorktreeAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val worktree = selectedWorktree(e) ?: return
        remove(project, worktree)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** Project View context group shown only when the selected directory is a known linked worktree. */
class ProjectViewWorktreeGroup : DefaultActionGroup() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = selectedWorktree(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private fun chooseWorktree(
    project: Project,
    includeMain: Boolean,
    title: String,
    filter: (WorktreeInfo) -> Boolean = { true },
    onChosen: (WorktreeInfo) -> Unit,
) {
    loadWorktrees(project, "Loading Git worktrees") { snapshot ->
        val worktrees = snapshot.worktrees.filter { (includeMain || !it.isMain) && filter(it) }
        if (worktrees.isEmpty()) {
            Messages.showInfoMessage(project, "No worktrees found.", title)
            return@loadWorktrees
        }
        val dialog = WorktreePickerDialog(project, worktrees, title)
        if (dialog.showAndGet()) {
            dialog.selectedWorktree?.let(onChosen)
        }
    }
}

private fun remove(
    project: Project,
    worktree: WorktreeInfo,
) {
    runBackground(
        project = project,
        title = "Checking Git worktree",
        work = { project.service<WorktreeGitService>().removalPreflight(worktree) },
    ) { preflight ->
        if (preflight.refuse) {
            Messages.showErrorDialog(project, "The main worktree cannot be removed.", "Remove Worktree")
            return@runBackground
        }
        val dialog = RemoveWorktreeDialog(project, worktree, preflight)
        if (dialog.showAndGet()) {
            removeWorktree(project, worktree, force = dialog.force, deleteBranch = dialog.deleteBranch)
        }
    }
}

private fun removeWorktree(
    project: Project,
    worktree: WorktreeInfo,
    force: Boolean,
    deleteBranch: Boolean = false,
) {
    runBackground(
        project = project,
        title = if (force) "Force removing Git worktree" else "Removing Git worktree",
        work = {
            project.service<WorktreeGitService>().removeWorktree(RemoveWorktreeRequest(worktree, force = force, deleteBranch = deleteBranch)).also {
                if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
            }
        },
    ) { result ->
        when {
            result.success -> Notifications.info(project, "Worktree removed", worktree.path.toString())
            !force && result.requiresForceRetry() -> retryRemoveWithForce(project, worktree, result, deleteBranch)
            else -> Notifications.error(project, "Remove failed", result.output.ifBlank { "Git command failed" })
        }
    }
}

private fun retryRemoveWithForce(
    project: Project,
    worktree: WorktreeInfo,
    result: WorktreeCommandResult,
    deleteBranch: Boolean,
) {
    val message =
        buildString {
            append("Git refused to remove this worktree without --force.\n\n")
            append(result.output.ifBlank { "The worktree may contain dirty, ignored, or generated files." })
            append("\n\nRetry with force?")
        }
    if (Messages.showYesNoDialog(project, message, "Force Remove Worktree", null) == Messages.YES) {
        removeWorktree(project, worktree, force = true, deleteBranch = deleteBranch)
    }
}

private fun reapplyCarryOver(
    project: Project,
    worktree: WorktreeInfo,
) {
    project.service<WorktreeCarryOverService>().reapplyCarryOverAsync(worktree) { result ->
        CarryOverResultDialog.showResult(project, result)
        project.service<WorktreeCacheService>().refreshAsync()
    }
}

private fun selectedWorktree(e: AnActionEvent): WorktreeInfo? {
    val project = e.project ?: return null
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    val path = Path.of(file.path)
    return project
        .service<WorktreeCacheService>()
        .current()
        .worktrees
        .firstOrNull { PathUtil.samePath(it.path, path) && !it.isMain }
}

private fun openSelected(e: AnActionEvent) {
    val project = e.project ?: return
    val worktree = selectedWorktree(e) ?: return
    project.service<WorktreeOpenService>().openWorktreeAsync(worktree)
}

internal fun createWorktree(
    project: Project,
    request: AddWorktreeRequest,
    openAfterCreate: Boolean,
) {
    runBackground(
        project = project,
        title = "Creating Git worktree",
        work = {
            val result = project.service<WorktreeGitService>().addWorktree(request)
            val created =
                if (result.success && openAfterCreate) {
                    project
                        .service<WorktreeCacheService>()
                        .refreshBlocking()
                        .worktrees
                        .firstOrNull { PathUtil.samePath(it.path, request.targetPath) }
                } else {
                    null
                }
            CreateResult(result, created)
        },
    ) { created ->
        if (created.result.success) {
            Notifications.info(project, "Worktree created", request.targetPath.toString())
            created.worktree?.let { worktree ->
                if (openAfterCreate) project.service<WorktreeOpenService>().openWorktreeAsync(worktree)
            }
        } else {
            Notifications.error(project, "Unable to create worktree", created.result.output.ifBlank { "Git command failed" })
        }
    }
}

private fun loadMainRoot(
    project: Project,
    title: String,
    onLoaded: (Path) -> Unit,
) {
    loadWorktrees(project, title) { snapshot ->
        val root = snapshot.worktrees.firstOrNull { it.isMain }?.repositoryRoot
        if (root == null) {
            Messages.showInfoMessage(project, "No main worktree found.", title)
        } else {
            onLoaded(root)
        }
    }
}

private fun loadWorktrees(
    project: Project,
    title: String,
    onLoaded: (WorktreeCacheSnapshot) -> Unit,
) {
    runBackground(
        project = project,
        title = title,
        work = { project.service<WorktreeCacheService>().refreshBlocking() },
    ) { snapshot ->
        if (snapshot.error == null) {
            onLoaded(snapshot)
        } else {
            Notifications.error(project, "Unable to list worktrees", snapshot.error)
        }
    }
}

private fun runCommand(
    project: Project,
    title: String,
    command: () -> WorktreeCommandResult,
) {
    runBackground(project, title, command) { result ->
        if (result.success) {
            Notifications.info(project, title.removePrefix("Git ").removeSuffix("s"), result.output.ifBlank { "Done" })
        } else {
            Notifications.error(project, "$title failed", result.output.ifBlank { "Git command failed" })
        }
    }
}

private fun <T> runBackground(
    project: Project,
    title: String,
    work: () -> T,
    onSuccess: (T) -> Unit,
) {
    ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching(work)
                ApplicationManager.getApplication().invokeLater {
                    result.onSuccess(onSuccess)
                    result.onFailure {
                        Notifications.error(project, title, it.message ?: it.javaClass.simpleName)
                    }
                }
            }
        },
    )
}

private data class CreateResult(
    val result: WorktreeCommandResult,
    val worktree: WorktreeInfo?,
)

private class DisabledAction(
    text: String,
) : DumbAwareAction(text) {
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
