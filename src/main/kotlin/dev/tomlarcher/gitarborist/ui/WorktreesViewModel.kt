package dev.tomlarcher.gitarborist.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.tomlarcher.gitarborist.git.Contributor
import dev.tomlarcher.gitarborist.git.WorktreeCacheService
import dev.tomlarcher.gitarborist.git.WorktreeInfo
import dev.tomlarcher.gitarborist.git.WorktreeStatus
import dev.tomlarcher.gitarborist.settings.GitArboristSettingsResolver
import dev.tomlarcher.gitarborist.status.WorktreeStatusService
import dev.tomlarcher.gitarborist.util.PathUtil
import java.nio.file.Path
import java.time.Instant

/**
 * Headless state holder for the worktrees panel. Refreshes the worktree list off the UI thread, then
 * loads status asynchronously and rebuilds immutable [WorktreeRow]s, discarding results from
 * superseded refreshes. Owns no Swing components.
 */
class WorktreesViewModel(
    private val project: Project,
) {
    @Volatile
    var state: WorktreesUiState = WorktreesUiState(loading = true)
        private set

    @Volatile
    private var generation = 0

    fun refresh(onChanged: () -> Unit = {}) {
        val token = ++generation
        state = state.copy(loading = true, error = null)
        onChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = runCatching { project.service<WorktreeCacheService>().refreshBlocking() }
            val showRelative = GitArboristSettingsResolver.effective(project).showRelativeLocations
            ApplicationManager.getApplication().invokeLater {
                if (token != generation) return@invokeLater
                val loaded = snapshot.getOrNull()
                when {
                    snapshot.isFailure -> {
                        val cause = snapshot.exceptionOrNull()
                        state = WorktreesUiState(loading = false, error = cause?.message ?: cause?.javaClass?.simpleName ?: "Unknown error")
                        onChanged()
                    }
                    loaded?.error != null -> {
                        state = WorktreesUiState(loading = false, error = loaded.error)
                        onChanged()
                    }
                    else -> {
                        val worktrees = loaded?.worktrees.orEmpty()
                        val defaultBranch = worktrees.firstOrNull { it.isMain }?.branch ?: "main"
                        project.service<WorktreeStatusService>().reload(worktrees, defaultBranch) { statusByPath ->
                            if (token != generation) return@reload
                            val statuses = worktrees.associateWith { statusByPath[PathUtil.normalize(it.path)] }
                            state = WorktreesUiState(rows = worktrees.toRows(statuses, showRelative), loading = false)
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/** Immutable snapshot the panel renders: rows plus loading and error flags. */
data class WorktreesUiState(
    val rows: List<WorktreeRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Presentation-ready view of one worktree: preformatted labels alongside the raw [info] and [statusDetails]. */
data class WorktreeRow(
    val info: WorktreeInfo,
    val branch: String,
    val status: String,
    val headDelta: String,
    val mainDivergence: String,
    val remoteDivergence: String,
    val path: String,
    val commit: String,
    val age: String,
    val message: String,
    val safeToDelete: Boolean,
    val creator: String,
    val committers: List<Contributor>,
    val statusDetails: WorktreeStatus?,
)

private fun List<WorktreeInfo>.toRows(
    statuses: Map<WorktreeInfo, WorktreeStatus?>,
    showRelative: Boolean,
): List<WorktreeRow> =
    map { info ->
        val status = statuses[info]
        WorktreeRow(
            info = info,
            branch = info.branch ?: "detached",
            status = statusSymbolLabel(info, status),
            headDelta = headDeltaLabel(status),
            mainDivergence = divergenceLabel(status?.mainAhead, status?.mainBehind),
            remoteDivergence = divergenceLabel(status?.remoteAhead, status?.remoteBehind),
            path = locationLabel(showRelative, info.repositoryRoot, info.path),
            commit = status?.shortCommitHash ?: info.commitHash.take(8),
            age = ageLabel(status?.commitEpochSeconds),
            message = status?.commitMessage.orEmpty(),
            creator = status?.creatorName.orEmpty(),
            committers = status?.committers.orEmpty(),
            safeToDelete = status?.safeToDelete == true && !info.isMain,
            statusDetails = status,
        )
    }

internal fun locationLabel(
    showRelative: Boolean,
    repositoryRoot: Path,
    worktreePath: Path,
): String = if (showRelative) relativePathLabel(repositoryRoot, worktreePath) else PathUtil.normalize(worktreePath).toString()

internal fun statusSymbolLabel(
    info: WorktreeInfo,
    status: WorktreeStatus?,
): String =
    buildList {
        if (info.isCurrent) add("📍")
        if (info.isMain) add("🏠")
        if (info.isLocked) add("🔒")
        if (info.isPrunable) add("🧹")
        if (info.isDetached) add("🧭")
        if (status?.safeToDelete == true && !info.isMain) add("🗑️")
        if (status?.stagedCount?.let { it > 0 } == true) add("✅")
        if (status?.unstagedCount?.let { it > 0 } == true) add("✏️")
        if (status?.untrackedCount?.let { it > 0 } == true) add("❓")
        addDivergence(status?.mainAhead, status?.mainBehind, ahead = "⬆️", behind = "⬇️", diverged = "↕️")
        addDivergence(status?.remoteAhead, status?.remoteBehind, ahead = "📤", behind = "📥", diverged = "🔄")
    }.joinToString(" ").ifBlank { "·" }

private fun MutableList<String>.addDivergence(
    aheadCount: Int?,
    behindCount: Int?,
    ahead: String,
    behind: String,
    diverged: String,
) {
    val aheadValue = aheadCount ?: return
    val behindValue = behindCount ?: return
    when {
        aheadValue > 0 && behindValue > 0 -> add(diverged)
        aheadValue > 0 -> add(ahead)
        behindValue > 0 -> add(behind)
    }
}

internal fun headDeltaLabel(status: WorktreeStatus?): String {
    val added = status?.headAddedLines ?: 0
    val deleted = status?.headDeletedLines ?: 0
    return if (added == 0 && deleted == 0) "·" else "+$added -$deleted"
}

internal fun divergenceLabel(
    ahead: Int?,
    behind: Int?,
): String =
    if (ahead == null || behind == null) {
        "·"
    } else if (ahead == 0 && behind == 0) {
        "|"
    } else {
        "↑$ahead ↓$behind"
    }

internal fun relativePathLabel(
    repositoryRoot: Path,
    worktreePath: Path,
): String =
    runCatching {
        PathUtil
            .normalize(repositoryRoot)
            .relativize(PathUtil.normalize(worktreePath))
            .toString()
            .ifBlank { "." }
    }.getOrDefault(worktreePath.toString())

internal fun ageLabel(epochSeconds: Long?): String {
    val epoch = epochSeconds ?: return "·"
    val seconds = (Instant.now().epochSecond - epoch).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < HOUR_SECONDS -> "${seconds / 60}m"
        seconds < DAY_SECONDS -> "${seconds / HOUR_SECONDS}h"
        seconds < MONTH_SECONDS -> "${seconds / DAY_SECONDS}d"
        seconds < YEAR_SECONDS -> "${seconds / MONTH_SECONDS}mo"
        else -> "${seconds / YEAR_SECONDS}y"
    }
}

private const val HOUR_SECONDS = 60 * 60
private const val DAY_SECONDS = 24 * HOUR_SECONDS
private const val MONTH_SECONDS = 30 * DAY_SECONDS
private const val YEAR_SECONDS = 365 * DAY_SECONDS
