package dev.tomlarcher.gitarborist.git

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the real-git contract the "new worktree from remote branch" feature relies on:
 * `git worktree add -b <local> <path> <remote>/<branch>` creates a local branch that tracks the
 * remote-tracking ref. [RemoteWorktreePlanner.request] produces exactly this [AddWorktreeRequest]
 * shape, mirroring what [WorktreeGitService.addWorktree] dispatches to Git.
 */
class RemoteWorktreeIntegrationTest {
    @Test
    fun remoteWorktreePlannerRequestCreatesTrackingBranch() {
        // ── 1. Origin repo with a commit on feature/demo ────────────────────
        val origin = createTempDirectory("gwt-remote-origin")
        git(origin, "init")
        git(origin, "config", "user.email", "test@example.com")
        git(origin, "config", "user.name", "Git Arborist Test")
        origin.resolve("README.md").writeText("baseline\n")
        git(origin, "add", "README.md")
        git(origin, "commit", "-m", "baseline")
        git(origin, "branch", "feature/demo") // create branch; HEAD stays on default

        // ── 2. Clone so the clone has origin/feature/demo ───────────────────
        val cloneParent = createTempDirectory("gwt-remote-clone-parent")
        val clone = cloneParent.resolve("clone")
        git(cloneParent, "clone", origin.toString(), clone.toString())
        git(clone, "config", "user.email", "test@example.com")
        git(clone, "config", "user.name", "Git Arborist Test")

        // ── 3. Plan: turn remote branch into an AddWorktreeRequest ──────────
        val branch =
            RemoteBranch(
                remote = "origin",
                branchName = "feature/demo",
                trackingRef = "origin/feature/demo",
                shortHash = null,
            )
        val worktreePath = clone.resolveSibling("${clone.fileName}-feature-demo").normalize()
        val request =
            RemoteWorktreePlanner.request(
                repositoryRoot = clone,
                targetPath = worktreePath,
                branch = branch,
                localBranchName = "feature/demo",
            )

        // Confirm the request encodes the expected shape before we execute it.
        assertEquals("origin/feature/demo", request.sourceRef)
        assertEquals("feature/demo", request.branchName)
        assertTrue(request.createBranch)
        assertFalse(request.detach)

        // ── 4. Execute exactly what WorktreeGitService.addWorktree builds ───
        // addWorktree: "add", then (-b <branchName> if createBranch && branchName != null),
        // then (endOptions "-- "), then targetPath, sourceRef.
        val branchArgs =
            if (request.createBranch && request.branchName != null) {
                listOf("-b", request.branchName)
            } else {
                emptyList()
            }
        git(
            clone,
            "worktree",
            "add",
            *branchArgs.toTypedArray(),
            "--",
            request.targetPath.toString(),
            request.sourceRef,
        )

        // ── 5. Assert: worktree exists, branch exists, tracking is correct ──
        assertTrue(worktreePath.exists(), "Worktree directory must exist after git worktree add")

        val upstream = git(worktreePath, "rev-parse", "--abbrev-ref", "feature/demo@{upstream}").trim()
        assertEquals(
            "origin/feature/demo",
            upstream,
            "Local branch 'feature/demo' must track 'origin/feature/demo'",
        )
    }

    private fun git(
        cwd: Path,
        vararg args: String,
    ): String {
        cwd.createDirectories()
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed with $exit:\n$output" }
        return output
    }
}
