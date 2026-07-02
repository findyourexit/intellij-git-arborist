package dev.tomlarcher.gitarborist.ui

import dev.tomlarcher.gitarborist.git.Contributor
import dev.tomlarcher.gitarborist.git.WorktreeInfo
import dev.tomlarcher.gitarborist.git.WorktreeStatus
import java.time.Instant
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorktreeRowPresentationTest {
    @Test
    fun relativePathCanPointToSiblingWorktree() {
        assertEquals("../repo.feature", relativePathLabel(Path("/workspace/repo"), Path("/workspace/repo.feature")))
    }

    @Test
    fun locationLabelHonorsRelativeToggle() {
        assertEquals("../repo.feature", locationLabel(true, Path("/workspace/repo"), Path("/workspace/repo.feature")))
        assertEquals("/workspace/repo.feature", locationLabel(false, Path("/workspace/repo"), Path("/workspace/repo.feature")))
    }

    @Test
    fun divergenceLabelUsesCompactArrows() {
        assertEquals("|", divergenceLabel(0, 0))
        assertEquals("↑2 ↓1", divergenceLabel(2, 1))
        assertEquals("·", divergenceLabel(null, null))
    }

    @Test
    fun headDeltaLabelShowsChangedLinesOnlyWhenPresent() {
        assertEquals("·", headDeltaLabel(null))
        assertEquals("+12 -3", headDeltaLabel(WorktreeStatus(true, 1, 1, 0, null, null, headAddedLines = 12, headDeletedLines = 3)))
    }

    @Test
    fun statusSymbolsIncludeImportantState() {
        val info =
            WorktreeInfo(
                repositoryRoot = Path("/repo"),
                path = Path("/repo.feature"),
                branch = "feature",
                commitHash = "abcdef123456",
                isMain = false,
                isCurrent = true,
                isLocked = true,
                lockReason = "review",
                isPrunable = false,
                prunableReason = null,
                isDetached = false,
            )
        val status =
            WorktreeStatus(
                dirty = true,
                stagedCount = 1,
                unstagedCount = 1,
                untrackedCount = 1,
                ahead = null,
                behind = null,
                mainAhead = 2,
                mainBehind = 1,
                remoteAhead = 1,
                remoteBehind = 0,
            )

        val symbols = statusSymbolLabel(info, status)

        assertTrue("📍" in symbols)
        assertTrue("🔒" in symbols)
        assertTrue("✅" in symbols)
        assertTrue("✏️" in symbols)
        assertTrue("❓" in symbols)
        assertTrue("↕️" in symbols)
        assertTrue("📤" in symbols)
    }

    @Test
    fun listPresentationUsesBranchSubtitleAndMetadata() {
        val row = sampleRow()

        assertEquals("feature/demo", worktreeTitle(row))
        assertEquals("../repo.feature · abcdef12 · 2d", worktreeSubtitle(row))
        assertEquals("Update checkout flow", worktreeMessageLine(row))
        assertEquals("Update checkout flow · Creator: Ada Lovelace · Locked: review · Safe to delete", worktreeMeta(row))
    }

    @Test
    fun searchValuesIncludeStateWordsAndReasons() {
        val row = sampleRow()
        val values = rowSearchValues(row)

        assertTrue(rowMatchesFilter(values, listOf("feature", "safe")))
        assertTrue(rowMatchesFilter(values, listOf("locked", "review")))
        assertTrue(rowMatchesFilter(values, listOf("creator", "ada")))
        assertTrue(rowMatchesFilter(values, listOf("grace", "hopper")))
        assertTrue(rowMatchesFilter(values, listOf("grace@example.test")))

        val dirtyRow = row.copy(safeToDelete = false, statusDetails = row.statusDetails?.copy(dirty = true))
        assertTrue(rowMatchesFilter(rowSearchValues(dirtyRow), listOf("dirty")))
    }

    @Test
    fun contextualTooltipsFollowListRegion() {
        val row = sampleRow()

        assertTrue("Branch / ref" in worktreeTooltip(row, cellX = 16, cellY = 8, cellWidth = 800, cellHeight = 72).orEmpty())
        assertTrue("Absolute: /workspace/repo.feature" in worktreeTooltip(row, cellX = 16, cellY = 30, cellWidth = 800, cellHeight = 72).orEmpty())
        assertTrue("Commit: abcdef12" in worktreeTooltip(row, cellX = 420, cellY = 30, cellWidth = 800, cellHeight = 72).orEmpty())
        assertTrue("Creator: Ada Lovelace" in worktreeTooltip(row, cellX = 420, cellY = 30, cellWidth = 800, cellHeight = 72).orEmpty())
        assertTrue("State badges" in worktreeTooltip(row, cellX = 650, cellY = 30, cellWidth = 800, cellHeight = 72).orEmpty())
    }

    private fun sampleRow(): WorktreeRow {
        val info =
            WorktreeInfo(
                repositoryRoot = Path("/workspace/repo"),
                path = Path("/workspace/repo.feature"),
                branch = "feature/demo",
                commitHash = "abcdef123456",
                isMain = false,
                isCurrent = false,
                isLocked = true,
                lockReason = "review",
                isPrunable = false,
                prunableReason = null,
                isDetached = false,
            )
        val status =
            WorktreeStatus(
                dirty = false,
                stagedCount = 1,
                unstagedCount = 0,
                untrackedCount = 0,
                ahead = null,
                behind = null,
                shortCommitHash = "abcdef12",
                commitEpochSeconds =
                    Instant
                        .now()
                        .epochSecond - 2 * 24 * 60 * 60,
                commitMessage = "Update checkout flow",
                creatorName = "Ada Lovelace",
                creatorEmail = "ada@example.test",
                committers = listOf(Contributor("Ada Lovelace", "ada@example.test"), Contributor("Grace Hopper", "grace@example.test")),
                safeToDelete = true,
            )
        return WorktreeRow(
            info = info,
            branch = info.branch ?: "detached",
            status = statusSymbolLabel(info, status),
            headDelta = headDeltaLabel(status),
            mainDivergence = divergenceLabel(status.mainAhead, status.mainBehind),
            remoteDivergence = divergenceLabel(status.remoteAhead, status.remoteBehind),
            path = relativePathLabel(info.repositoryRoot, info.path),
            commit = status.shortCommitHash ?: info.commitHash.take(8),
            age = ageLabel(status.commitEpochSeconds),
            message = status.commitMessage.orEmpty(),
            creator = status.creatorName.orEmpty(),
            committers = status.committers,
            safeToDelete = true,
            statusDetails = status,
        )
    }
}
