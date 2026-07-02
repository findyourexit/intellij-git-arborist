package dev.tomlarcher.gitarborist.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteBranchParserTest {
    /** Builds one `git for-each-ref` output line: `refs/remotes/<name>\u0000<hash>` or just the refname when hash is null. */
    private fun ref(
        name: String,
        hash: String? = null,
    ) = "refs/remotes/$name" + (hash?.let { "\u0000$it" } ?: "")

    @Test
    fun parsesMultiRemoteListingIncludingHierarchicalBranchNames() {
        val output =
            listOf(
                ref("origin/main", "abc1234"),
                ref("origin/feature/x", "def5678"),
                ref("upstream/main", "ghi9012"),
            ).joinToString("\n")

        val result = RemoteBranchParser.parse(output, listOf("origin", "upstream"))

        assertEquals(3, result.size)

        val originMain = result[0]
        assertEquals("origin", originMain.remote)
        assertEquals("main", originMain.branchName)
        assertEquals("origin/main", originMain.trackingRef)
        assertEquals("abc1234", originMain.shortHash)

        val originFeature = result[1]
        assertEquals("origin", originFeature.remote)
        assertEquals("feature/x", originFeature.branchName) // slash preserved
        assertEquals("origin/feature/x", originFeature.trackingRef)
        assertEquals("def5678", originFeature.shortHash)

        val upstreamMain = result[2]
        assertEquals("upstream", upstreamMain.remote)
        assertEquals("main", upstreamMain.branchName)
        assertEquals("upstream/main", upstreamMain.trackingRef)
        assertEquals("ghi9012", upstreamMain.shortHash)
    }

    @Test
    fun skipsSymbolicHeadRef() {
        val output =
            listOf(
                ref("origin/HEAD"),
                ref("origin/main", "abc1234"),
            ).joinToString("\n")

        val result = RemoteBranchParser.parse(output, listOf("origin"))

        assertEquals(1, result.size)
        assertEquals("main", result[0].branchName)
    }

    @Test
    fun longestRemoteMatchWinsOverShorterPrefix() {
        // "origin" is a prefix of "origin-fork"; the longer name must win
        val output = ref("origin-fork/main", "abc1234")

        val result = RemoteBranchParser.parse(output, listOf("origin", "origin-fork"))

        assertEquals(1, result.size)
        assertEquals("origin-fork", result[0].remote)
        assertEquals("main", result[0].branchName)
    }

    @Test
    fun missingHashFieldYieldsNullShortHash() {
        // line has no NUL — second field absent
        val output = "refs/remotes/origin/main"

        val result = RemoteBranchParser.parse(output, listOf("origin"))

        assertEquals(1, result.size)
        assertNull(result[0].shortHash)
    }

    @Test
    fun refWithUnknownRemoteIsSkipped() {
        val output =
            listOf(
                ref("unknown-remote/main", "abc1234"),
                ref("origin/main", "def5678"),
            ).joinToString("\n")

        val result = RemoteBranchParser.parse(output, listOf("origin"))

        assertEquals(1, result.size)
        assertEquals("origin", result[0].remote)
        assertEquals("main", result[0].branchName)
    }

    @Test
    fun blankLinesAndTrailingCarriageReturnAreIgnored() {
        val output =
            listOf(
                ref("origin/main", "abc1234"),
                "", // blank line
                ref("origin/feature/y", "def5678") + "\r", // CRLF line ending
                "", // trailing blank
            ).joinToString("\n")

        val result = RemoteBranchParser.parse(output, listOf("origin"))

        assertEquals(2, result.size)
        assertEquals("main", result[0].branchName)
        assertEquals("abc1234", result[0].shortHash)
        assertEquals("feature/y", result[1].branchName)
        assertEquals("def5678", result[1].shortHash)
    }
}
