package dev.tomlarcher.gitarborist.git

import java.nio.file.Path

/**
 * Immutable description of a single Git worktree parsed from `git worktree list --porcelain`.
 *
 * @property repositoryRoot canonical main-worktree root this worktree belongs to
 * @property path absolute, normalized location of the worktree on disk
 * @property branch checked-out local branch, or `null` when detached
 * @property commitHash full commit hash at the worktree HEAD
 * @property isMain whether this is the main (non-linked) worktree
 * @property isCurrent whether this worktree backs the currently open project
 * @property isLocked whether the worktree is locked against pruning
 * @property isPrunable whether Git reports the worktree as prunable
 * @property isDetached whether HEAD is detached
 */
data class WorktreeInfo(
    val repositoryRoot: Path,
    val path: Path,
    val branch: String?,
    val commitHash: String,
    val isMain: Boolean,
    val isCurrent: Boolean,
    val isLocked: Boolean,
    val lockReason: String?,
    val isPrunable: Boolean,
    val prunableReason: String?,
    val isDetached: Boolean,
)

/**
 * Asynchronously loaded status for one worktree. Counts and divergence default to a clean, unknown
 * state so a row can render before its status finishes loading. The `main*` fields compare against
 * the default branch; the `remote*` fields compare against the tracked upstream.
 */
data class WorktreeStatus(
    val dirty: Boolean,
    val stagedCount: Int,
    val unstagedCount: Int,
    val untrackedCount: Int,
    val ahead: Int?,
    val behind: Int?,
    val headAddedLines: Int = 0,
    val headDeletedLines: Int = 0,
    val mainAhead: Int? = null,
    val mainBehind: Int? = null,
    val remoteAhead: Int? = ahead,
    val remoteBehind: Int? = behind,
    val shortCommitHash: String? = null,
    val commitEpochSeconds: Long? = null,
    val commitMessage: String? = null,
    val safeToDelete: Boolean = false,
    val creatorName: String? = null,
    val creatorEmail: String? = null,
    val creatorEpochSeconds: Long? = null,
)

/**
 * A remote-tracking branch offered in the "new worktree from a remote branch" flow.
 *
 * @property remote the owning remote's name, e.g. `origin`
 * @property branchName the branch's name on the remote, without the remote prefix, e.g. `feature/x`
 * @property trackingRef the remote-tracking ref used as the `git worktree add` start point, e.g. `origin/feature/x`
 * @property shortHash abbreviated tip commit hash, or `null` when the tip is unknown
 */
data class RemoteBranch(
    val remote: String,
    val branchName: String,
    val trackingRef: String,
    val shortHash: String?,
)

/** Parameters for `git worktree add`: where to create the worktree and how to seed its branch or ref. */
data class AddWorktreeRequest(
    val repositoryRoot: Path,
    val targetPath: Path,
    val sourceRef: String,
    val branchName: String?,
    val createBranch: Boolean,
    val detach: Boolean,
)

/** Parameters for `git worktree remove`, including optional force and backing-branch deletion. */
data class RemoveWorktreeRequest(
    val worktree: WorktreeInfo,
    val force: Boolean = false,
    val deleteBranch: Boolean = false,
)

/** Captured success flag and merged stdout/stderr from a worktree Git command. */
data class WorktreeCommandResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
) {
    val output: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * Heuristic for whether a failed worktree command failed because git wanted `--force` (dirty, locked,
 * or generated files), so the UI can offer a retry-with-force instead of a dead-end error.
 */
internal fun WorktreeCommandResult.requiresForceRetry(): Boolean {
    val lower = output.lowercase()
    return "--force" in lower || "force" in lower || "dirty" in lower || "locked" in lower
}
