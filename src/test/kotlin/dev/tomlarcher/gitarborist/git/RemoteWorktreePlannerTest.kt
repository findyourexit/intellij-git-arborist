package dev.tomlarcher.gitarborist.git

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteWorktreePlannerTest {
    // ── request() ────────────────────────────────────────────────────────────

    @Test
    fun requestSetsSourceRefFromTrackingRef() {
        val branch = RemoteBranch("origin", "feature/x", "origin/feature/x", null)
        val request =
            RemoteWorktreePlanner.request(
                repositoryRoot = Path("/repo"),
                targetPath = Path("/repo/.worktrees/fx"),
                branch = branch,
                localBranchName = "feature/x",
            )
        assertEquals("origin/feature/x", request.sourceRef)
    }

    @Test
    fun requestAlwaysCreatesBranchAndNeverDetaches() {
        val branch = RemoteBranch("origin", "main", "origin/main", null)
        val request =
            RemoteWorktreePlanner.request(
                repositoryRoot = Path("/repo"),
                targetPath = Path("/repo/.worktrees/main"),
                branch = branch,
                localBranchName = "main",
            )
        assertTrue(request.createBranch)
        assertFalse(request.detach)
    }

    @Test
    fun requestUsesTrimmedLocalBranchNameWhenProvided() {
        val branch = RemoteBranch("upstream", "main", "upstream/main", "abc123")
        val request =
            RemoteWorktreePlanner.request(
                repositoryRoot = Path("/repo"),
                targetPath = Path("/repo/.worktrees/main"),
                branch = branch,
                localBranchName = "  my-local  ",
            )
        assertEquals("my-local", request.branchName)
    }

    @Test
    fun requestFallsBackToBranchNameWhenLocalNameIsBlankOrEmpty() {
        val branch = RemoteBranch("origin", "feature/x", "origin/feature/x", null)
        for (localName in listOf("", "   ", "\t\n")) {
            val request =
                RemoteWorktreePlanner.request(
                    repositoryRoot = Path("/repo"),
                    targetPath = Path("/repo/.worktrees/fx"),
                    branch = branch,
                    localBranchName = localName,
                )
            assertEquals(
                "feature/x",
                request.branchName,
                "Expected fallback to branch.branchName for localBranchName=${localName.repr()}",
            )
        }
    }

    // ── sorted() ─────────────────────────────────────────────────────────────

    @Test
    fun sortedOrdersBranchesCaseInsensitivelyAndDedupsByTrackingRef() {
        // Case-sensitively 'B'(66) < 'a'(97), so "origin/Beta" < "origin/alpha".
        // Case-insensitively: alpha < Beta < main — the expected order.
        // alpha2 shares a trackingRef with alpha1; it must be collapsed and alpha1 must survive.
        val alpha1 = RemoteBranch("origin", "alpha", "origin/alpha", "aaa")
        val alpha2 = RemoteBranch("origin", "alpha", "origin/alpha", "bbb") // duplicate trackingRef
        val beta = RemoteBranch("origin", "Beta", "origin/Beta", null)
        val main = RemoteBranch("upstream", "main", "upstream/main", null)

        val result = RemoteWorktreePlanner.sorted(listOf(alpha1, main, beta, alpha2))

        assertEquals(3, result.size, "Duplicate trackingRef must be collapsed to one entry")
        assertEquals("origin/alpha", result[0].trackingRef)
        assertEquals("origin/Beta", result[1].trackingRef)
        assertEquals("upstream/main", result[2].trackingRef)
        // First-seen entry survives deduplication
        assertEquals("aaa", result[0].shortHash)
    }

    // ── defaultLocalBranchName() ─────────────────────────────────────────────

    @Test
    fun defaultLocalBranchNameReturnsBranchName() {
        val branch = RemoteBranch("origin", "develop", "origin/develop", null)
        assertEquals("develop", RemoteWorktreePlanner.defaultLocalBranchName(branch))
    }

    @Test
    fun defaultLocalBranchNamePreservesSlashesInHierarchicalBranchName() {
        val branch = RemoteBranch("origin", "feature/x", "origin/feature/x", null)
        assertEquals("feature/x", RemoteWorktreePlanner.defaultLocalBranchName(branch))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun String.repr(): String = replace("\t", "\\t").replace("\n", "\\n")
}
