package dev.tomlarcher.gitarborist.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarResolutionTest {
    @Test
    fun parsesGitHubRepoFromHttpsAndSsh() {
        val expected = GitHubRepo("findyourexit", "intellij-git-arborist")
        assertEquals(expected, parseGitHubRepo("https://github.com/findyourexit/intellij-git-arborist.git"))
        assertEquals(expected, parseGitHubRepo("https://github.com/findyourexit/intellij-git-arborist"))
        assertEquals(expected, parseGitHubRepo("git@github.com:findyourexit/intellij-git-arborist.git"))
        assertEquals(expected, parseGitHubRepo("ssh://git@github.com/findyourexit/intellij-git-arborist"))
        assertEquals(expected, parseGitHubRepo("https://token@github.com/findyourexit/intellij-git-arborist.git"))
    }

    @Test
    fun rejectsNonGitHubRemotes() {
        assertNull(parseGitHubRepo("https://gitlab.com/group/project.git"))
        assertNull(parseGitHubRepo("git@bitbucket.org:team/repo.git"))
        assertNull(parseGitHubRepo("https://github.enterprise.example.com/owner/repo.git"))
        assertNull(parseGitHubRepo("https://github.com/owner"))
    }

    @Test
    fun buildsGitHubAvatarUrlsFromNoReplyEmails() {
        assertEquals(
            "https://avatars.githubusercontent.com/u/583231?s=64",
            gitHubNoReplyAvatarUrl("583231+octocat@users.noreply.github.com"),
        )
        assertEquals(
            "https://github.com/octocat.png?size=64",
            gitHubNoReplyAvatarUrl("octocat@users.noreply.github.com"),
        )
        assertNull(gitHubNoReplyAvatarUrl("octocat@example.com"))
    }

    @Test
    fun extractsAvatarFromCommitsApiJson() {
        val json =
            """[{"sha":"a","commit":{"author":{"email":"a@b.com"}},""" +
                """"author":{"login":"gregsh","avatar_url":"https://avatars.githubusercontent.com/u/958865?v=4"}}]"""
        assertEquals("https://avatars.githubusercontent.com/u/958865?v=4", parseCommitAuthorAvatar(json))
    }

    @Test
    fun returnsNullWhenCommitHasNoGitHubAuthor() {
        assertNull(parseCommitAuthorAvatar("""[{"sha":"a","author":null}]"""))
        assertNull(parseCommitAuthorAvatar("[]"))
        assertNull(parseCommitAuthorAvatar("not json"))
    }
}
