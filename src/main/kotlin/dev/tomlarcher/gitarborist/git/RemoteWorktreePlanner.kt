package dev.tomlarcher.gitarborist.git

import java.nio.file.Path

/**
 * Pure planning for the "new worktree from a remote branch" flow: how remote branches are ordered for
 * the picker, what local branch name a remote branch defaults to, and how a selection turns into an
 * [AddWorktreeRequest]. Kept free of Git4Idea and Swing types so it is unit-testable in isolation.
 */
object RemoteWorktreePlanner {
    /** Orders remote branches by remote then branch name, case-insensitively, for a stable picker. */
    fun sorted(branches: List<RemoteBranch>): List<RemoteBranch> =
        branches
            .distinctBy { it.trackingRef }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.trackingRef })

    /** The local branch a remote branch seeds by default: its name on the remote, minus the remote prefix. */
    fun defaultLocalBranchName(branch: RemoteBranch): String = branch.branchName

    /**
     * Turns a selected remote branch into a create request: a new local branch tracking the remote
     * branch, seeded from the remote-tracking ref. Git sets up tracking automatically because the start
     * point is a remote-tracking ref, matching `git worktree add -b <local> <path> <remote>/<branch>`.
     */
    fun request(
        repositoryRoot: Path,
        targetPath: Path,
        branch: RemoteBranch,
        localBranchName: String,
    ): AddWorktreeRequest =
        AddWorktreeRequest(
            repositoryRoot = repositoryRoot,
            targetPath = targetPath,
            sourceRef = branch.trackingRef,
            branchName = localBranchName.trim().ifBlank { branch.branchName },
            createBranch = true,
            detach = false,
        )
}
