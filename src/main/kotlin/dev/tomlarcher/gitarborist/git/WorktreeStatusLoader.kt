package dev.tomlarcher.gitarborist.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.nio.file.Path

/**
 * Loads per-worktree working-tree and history status through Git4Idea commands: dirty/staged/
 * untracked counts, HEAD line delta, default-branch and remote divergence, latest commit metadata,
 * branch creator, and the safe-to-delete evaluation. Pure command execution with no UI or caching;
 * callers invoke it off the EDT.
 */
class WorktreeStatusLoader(
    private val project: Project,
) {
    fun load(
        path: Path,
        defaultBranch: String? = null,
        branchName: String? = null,
    ): WorktreeStatus {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return emptyStatus()
        val handler = GitLineHandler(project, virtualFile, GitCommand.STATUS)
        handler.addParameters("--porcelain=v1", "--branch")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyStatus()

        val base = parseStatus(result.getOutputAsJoinedString())
        val headDelta = diffAgainstHead(virtualFile)
        val mainDivergence = defaultBranch?.let { divergence(virtualFile, it) }
        val remoteDivergence = divergence(virtualFile, "@{upstream}")
        val commit = latestCommit(virtualFile)
        val creator = branchName?.let { branchCreator(virtualFile, it) }
        return base.copy(
            headAddedLines = headDelta.added,
            headDeletedLines = headDelta.deleted,
            mainAhead = mainDivergence?.ahead,
            mainBehind = mainDivergence?.behind,
            remoteAhead = remoteDivergence?.ahead ?: base.ahead,
            remoteBehind = remoteDivergence?.behind ?: base.behind,
            shortCommitHash = commit?.shortHash,
            commitEpochSeconds = commit?.epochSeconds,
            commitMessage = commit?.message,
            creatorName = creator?.name ?: commit?.authorName,
            creatorEmail = creator?.email ?: commit?.authorEmail,
            creatorEpochSeconds = creator?.epochSeconds ?: commit?.epochSeconds,
            committers = committers(virtualFile, defaultBranch),
            safeToDelete = safeToDelete(virtualFile, base, defaultBranch),
        )
    }

    private fun emptyStatus(): WorktreeStatus = WorktreeStatus(false, 0, 0, 0, null, null)

    private fun diffAgainstHead(root: VirtualFile): LineDelta {
        val handler = GitLineHandler(project, root, GitCommand.DIFF)
        handler.addParameters("--numstat", "HEAD", "--")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) parseNumstat(result.getOutputAsJoinedString()) else LineDelta.Zero
    }

    private fun divergence(
        root: VirtualFile,
        baseRef: String,
    ): Divergence? = divergenceBetween(root, baseRef, "HEAD")

    private fun divergenceBetween(
        root: VirtualFile,
        baseRef: String,
        headRef: String,
    ): Divergence? {
        val handler = GitLineHandler(project, root, GitCommand.REV_LIST)
        handler.addParameters("--left-right", "--count", "$baseRef...$headRef")
        val result = Git.getInstance().runCommand(handler)
        val parts = result.getOutputAsJoinedString().trim().split(Regex("\\s+"))
        val behind = if (result.success()) parts.getOrNull(0)?.toIntOrNull() else null
        val ahead = if (result.success()) parts.getOrNull(1)?.toIntOrNull() else null
        return if (ahead == null || behind == null) null else Divergence(ahead = ahead, behind = behind)
    }

    private fun latestCommit(root: VirtualFile): CommitDetails? {
        val handler = GitLineHandler(project, root, GitCommand.LOG)
        handler.addParameters("-1", "--format=%h%x00%ct%x00%s%x00%an%x00%ae")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        val parts = result.getOutputAsJoinedString().trim().split('\u0000', limit = 5)
        return CommitDetails(
            shortHash = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null,
            epochSeconds = parts.getOrNull(1)?.toLongOrNull(),
            message = parts.getOrNull(2).orEmpty(),
            authorName = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
            authorEmail = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }

    private fun branchCreator(
        root: VirtualFile,
        branchName: String,
    ): CreatorDetails? {
        val handler = GitLineHandler(project, root, GitCommand.REF_LOG)
        handler.addParameters("show", "--reverse", "--format=%an%x00%ae%x00%ct", branchName)
        val result = Git.getInstance().runCommand(handler)
        val line = result.getOutputAsJoinedString().lineSequence().firstOrNull { result.success() && it.isNotBlank() }
        val parts = line?.split('\u0000', limit = 3).orEmpty()
        val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        return name?.let {
            CreatorDetails(
                name = it,
                email = parts.getOrNull(1)?.takeIf(String::isNotBlank),
                epochSeconds = parts.getOrNull(2)?.toLongOrNull(),
            )
        }
    }

    private fun committers(
        root: VirtualFile,
        defaultBranch: String?,
    ): List<Contributor> {
        val unique = committerContributors(root, defaultBranch?.let { "$it..HEAD" })
        return unique.ifEmpty { committerContributors(root, null) }
    }

    private fun committerContributors(
        root: VirtualFile,
        range: String?,
    ): List<Contributor> {
        val handler = GitLineHandler(project, root, GitCommand.LOG)
        handler.addParameters("--format=%an%x00%ae")
        if (range != null) handler.addParameters(range) else handler.addParameters("-$RECENT_COMMIT_SCAN")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return result
            .getOutputAsJoinedString()
            .lineSequence()
            .map { it.split('\u0000', limit = 2) }
            .map { Contributor(it.getOrNull(0).orEmpty().trim(), it.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.email ?: it.name }
            .take(MAX_COMMITTERS)
            .toList()
    }

    private fun safeToDelete(
        root: VirtualFile,
        status: WorktreeStatus,
        defaultBranch: String?,
    ): Boolean {
        val target = defaultBranch?.let { safeDeleteTarget(root, it) } ?: return false
        return SafeDeleteEvaluator.isSafe(
            cleanWorkingTree = !status.dirty,
            sameCommit = revParse(root, "HEAD") == revParse(root, target),
            branchIsAncestor = isAncestor(root, "HEAD", target),
            noAddedChanges = diffNameOnly(root, "$target...HEAD")?.isEmpty() == true,
            treesMatch = revParse(root, "HEAD^{tree}") == revParse(root, "$target^{tree}"),
        )
    }

    private fun safeDeleteTarget(
        root: VirtualFile,
        defaultBranch: String,
    ): String {
        val upstream = "$defaultBranch@{upstream}"
        val defaultToUpstream = divergenceBetween(root, upstream, defaultBranch)
        return if (defaultToUpstream?.let { it.ahead > 0 && it.behind == 0 } == true) upstream else defaultBranch
    }

    private fun isAncestor(
        root: VirtualFile,
        ancestor: String,
        descendant: String,
    ): Boolean {
        val handler = GitLineHandler(project, root, GitCommand.MERGE_BASE)
        handler.addParameters("--is-ancestor", ancestor, descendant)
        return Git.getInstance().runCommand(handler).success()
    }

    private fun revParse(
        root: VirtualFile,
        ref: String,
    ): String? {
        val handler = GitLineHandler(project, root, GitCommand.REV_PARSE)
        handler.addParameters(ref)
        val result = Git.getInstance().runCommand(handler)
        return result.getOutputAsJoinedString().trim().takeIf { result.success() && it.isNotBlank() }
    }

    private fun diffNameOnly(
        root: VirtualFile,
        range: String,
    ): String? {
        val handler = GitLineHandler(project, root, GitCommand.DIFF)
        handler.addParameters("--name-only", range, "--")
        val result = Git.getInstance().runCommand(handler)
        return result.getOutputAsJoinedString().trim().takeIf { result.success() }
    }

    private fun parseNumstat(output: String): LineDelta =
        output.lineSequence().fold(LineDelta.Zero) { acc, line ->
            val parts = line.split('\t')
            val added = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val deleted = parts.getOrNull(1)?.toIntOrNull() ?: 0
            LineDelta(acc.added + added, acc.deleted + deleted)
        }

    private fun parseStatus(output: String): WorktreeStatus {
        var staged = 0
        var unstaged = 0
        var untracked = 0
        var ahead: Int? = null
        var behind: Int? = null
        output.lineSequence().forEach { line ->
            when {
                line.startsWith("## ") -> {
                    val aheadMatch = Regex("ahead (\\d+)").find(line)
                    val behindMatch = Regex("behind (\\d+)").find(line)
                    ahead = aheadMatch?.groupValues?.get(1)?.toIntOrNull()
                    behind = behindMatch?.groupValues?.get(1)?.toIntOrNull()
                }
                line.startsWith("??") -> untracked++
                line.length >= 2 -> {
                    if (line[0] != ' ') staged++
                    if (line[1] != ' ') unstaged++
                }
            }
        }
        return WorktreeStatus(
            dirty = staged > 0 || unstaged > 0 || untracked > 0,
            stagedCount = staged,
            unstagedCount = unstaged,
            untrackedCount = untracked,
            ahead = ahead,
            behind = behind,
        )
    }
}

private data class LineDelta(
    val added: Int,
    val deleted: Int,
) {
    companion object {
        val Zero = LineDelta(0, 0)
    }
}

private data class Divergence(
    val ahead: Int,
    val behind: Int,
)

private data class CommitDetails(
    val shortHash: String,
    val epochSeconds: Long?,
    val message: String,
    val authorName: String?,
    val authorEmail: String?,
)

private data class CreatorDetails(
    val name: String,
    val email: String?,
    val epochSeconds: Long?,
)

private const val MAX_COMMITTERS = 30
private const val RECENT_COMMIT_SCAN = 30
