package dev.tomlarcher.gitarborist.git

/**
 * Parses `git for-each-ref refs/remotes` output into [RemoteBranch] rows. Each line is
 * `<refname>\u0000<short-hash>` (NUL-separated). The remote is split from the branch name using the
 * repository's known remote names — longest match wins, so both remotes and branch names that contain
 * slashes resolve correctly — and the `refs/remotes/<remote>/HEAD` symbolic ref is skipped.
 *
 * Kept free of Git4Idea and Swing types so it is unit-testable against raw git output, mirroring
 * [WorktreePorcelainParser].
 */
object RemoteBranchParser {
    fun parse(
        forEachRefOutput: String,
        remoteNames: Collection<String>,
    ): List<RemoteBranch> {
        val remotes = remoteNames.filter(String::isNotBlank).sortedByDescending { it.length }
        return forEachRefOutput
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .mapNotNull { line -> parseLine(line, remotes) }
            .toList()
    }

    private fun parseLine(
        line: String,
        remotes: List<String>,
    ): RemoteBranch? {
        val fields = line.split('\u0000')
        val trackingRef = fields.getOrNull(0)?.removePrefix("refs/remotes/")?.takeIf(String::isNotBlank) ?: return null
        val shortHash = fields.getOrNull(1)?.takeIf(String::isNotBlank)
        val remote = remotes.firstOrNull { trackingRef == it || trackingRef.startsWith("$it/") } ?: return null
        val branchName = trackingRef.removePrefix("$remote/")
        return if (branchName.isBlank() || branchName == "HEAD") {
            null
        } else {
            RemoteBranch(remote = remote, branchName = branchName, trackingRef = trackingRef, shortHash = shortHash)
        }
    }
}
