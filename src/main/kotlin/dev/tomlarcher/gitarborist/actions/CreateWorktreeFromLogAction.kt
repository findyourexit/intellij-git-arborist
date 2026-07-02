package dev.tomlarcher.gitarborist.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.vcs.log.VcsLogDataKeys
import com.intellij.vcs.log.VcsRef
import dev.tomlarcher.gitarborist.settings.GitArboristSettingsResolver
import dev.tomlarcher.gitarborist.ui.CreateWorktreeDialog
import git4idea.repo.GitRepositoryManager

/**
 * VCS Log context-menu action: create a worktree from the branch ref under the caret in the Git Log.
 *
 * This is the verifier-safe way to offer "create worktree" from an IDE branch listing outside the tool
 * window. It reads only public [VcsLogDataKeys], unlike the Git Branches popup whose selection data keys
 * are `@ApiStatus.Internal` (and which already ships a built-in "New Worktree" action of its own). A
 * remote branch seeds a new local tracking branch; a local branch is checked out into the new worktree.
 */
class CreateWorktreeFromLogAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ref = selectedBranchRef(e) ?: return
        val repository =
            GitRepositoryManager.getInstance(project).repositories.firstOrNull { it.root == ref.root }
                ?: run {
                    Messages.showInfoMessage(project, "No Git repository for the selected branch.", "Create Worktree")
                    return
                }
        val repositoryRoot = repository.root.toNioPath()
        val settings = GitArboristSettingsResolver.effective(project)
        val remoteBranch = repository.branches.remoteBranches.firstOrNull { it.name == ref.name }
        val dialog =
            if (remoteBranch != null) {
                CreateWorktreeDialog(
                    project,
                    repositoryRoot,
                    settings.openAfterCreate,
                    settings.defaultWorktreeDirectory,
                    initialStartingPoint = remoteBranch.nameForLocalOperations,
                    initialBranchName = remoteBranch.nameForRemoteOperations,
                )
            } else {
                CreateWorktreeDialog(
                    project,
                    repositoryRoot,
                    settings.openAfterCreate,
                    settings.defaultWorktreeDirectory,
                    initialStartingPoint = ref.name,
                    initialTargetName = ref.name,
                )
            }
        if (dialog.showAndGet()) createWorktree(project, dialog.request(), dialog.shouldOpenAfterCreate)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && selectedBranchRef(e) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun selectedBranchRef(e: AnActionEvent): VcsRef? = e.getData(VcsLogDataKeys.VCS_LOG_REFS)?.firstOrNull { it.type.isBranch }
}
