package dev.tomlarcher.gitarborist.ui

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import dev.tomlarcher.gitarborist.settings.GitArboristSettings
import git4idea.config.GitExecutableManager
import java.awt.Image
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** A GitHub `owner/repo` parsed from a `github.com` remote URL. */
data class GitHubRepo(
    val owner: String,
    val name: String,
)

/**
 * Resolves contributor email addresses to avatar images, cached for the IDE session. Resolution is
 * layered, mirroring the established approach in tools like GitLens:
 *  1. GitHub `users.noreply.github.com` commit emails map straight to that user's GitHub avatar.
 *  2. When the user opts in (see [GitArboristSettings.State.resolveGitHubAvatars]) and a worktree's
 *     remote is `github.com`, other addresses are resolved through the GitHub commits API, which maps
 *     the commit email to its account server-side — the same identity the Pull Requests window shows.
 *     The request is authenticated with the GitHub token from `git credential fill` (the credential
 *     the IDE and Git already share) for the higher rate limit and private repositories.
 *  3. Everything else falls back to Gravatar, then to a drawn monogram.
 *
 * Lookups are non-blocking: [image] returns a cached image or `null` (callers draw a monogram),
 * kicking off one background fetch per email. When a fetch lands, registered repaint hooks fire so
 * rows redraw with the real avatar. Misses are remembered so an address is fetched at most once.
 */
@Service
class AvatarService {
    private val cache = ConcurrentHashMap<String, Image>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val repaintHooks = ConcurrentHashMap.newKeySet<Runnable>()

    private val repoByRoot = ConcurrentHashMap<String, GitHubRepo>()
    private val nonGitHubRoots = ConcurrentHashMap.newKeySet<String>()

    private val tokenLock = Any()

    @Volatile private var tokenResolved = false

    @Volatile private var cachedToken: String? = null

    /** Set when the GitHub API reports the rate limit is exhausted, so we stop calling until restart. */
    @Volatile private var apiRateLimited = false

    fun addRepaintHook(hook: Runnable) = repaintHooks.add(hook)

    fun removeRepaintHook(hook: Runnable) = repaintHooks.remove(hook)

    /**
     * Cached avatar for [email], or `null` while absent; schedules a one-shot fetch on the first miss.
     * [repositoryRoot] enables GitHub API resolution for that worktree's `github.com` remote.
     */
    fun image(
        email: String?,
        repositoryRoot: Path? = null,
    ): Image? {
        val key = email?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        cache[key]?.let { return it }
        if (key !in misses) request(key, repositoryRoot)
        return null
    }

    private fun request(
        key: String,
        repositoryRoot: Path?,
    ) {
        if (!inFlight.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val image = fetch(key, repositoryRoot)
            if (image != null) cache[key] = image else misses.add(key)
            inFlight.remove(key)
            if (image != null) repaintHooks.forEach(Runnable::run)
        }
    }

    private fun fetch(
        email: String,
        repositoryRoot: Path?,
    ): Image? {
        gitHubNoReplyAvatarUrl(email)?.let { return readImage(it) }

        if (repositoryRoot != null && gitHubAvatarsEnabled() && !apiRateLimited) {
            gitHubRepoFor(repositoryRoot)?.let { repo ->
                resolveCommitAvatarUrl(repo, email)?.let { return readImage(it) }
            }
        }

        return readImage(gravatarUrl(email))
    }

    private fun readImage(url: String): Image? =
        try {
            val bytes =
                HttpRequests
                    .request(url)
                    .connectTimeout(TIMEOUT_MS)
                    .readTimeout(TIMEOUT_MS)
                    .readBytes(null)
            ImageIO.read(bytes.inputStream())
        } catch (_: IOException) {
            null
        } catch (e: RuntimeException) {
            thisLogger().debug("Avatar image fetch failed", e)
            null
        }

    private fun gitHubAvatarsEnabled(): Boolean = service<GitArboristSettings>().state.resolveGitHubAvatars

    private fun gitHubRepoFor(root: Path): GitHubRepo? {
        val key = root.toString()
        repoByRoot[key]?.let { return it }
        if (key in nonGitHubRoots) return null
        val repo =
            runCatching {
                val config = root.resolve(".git").resolve("config")
                if (!Files.isRegularFile(config)) return@runCatching null
                parseGitConfigRemoteUrl(Files.readString(config))?.let(::parseGitHubRepo)
            }.getOrNull()
        if (repo != null) repoByRoot[key] = repo else nonGitHubRoots.add(key)
        return repo
    }

    private fun resolveCommitAvatarUrl(
        repo: GitHubRepo,
        email: String,
    ): String? =
        try {
            val encoded = URLEncoder.encode(email, Charsets.UTF_8)
            val api = "https://api.github.com/repos/${repo.owner}/${repo.name}/commits?author=$encoded&per_page=1"
            val token = gitHubToken()
            HttpRequests
                .request(api)
                .accept("application/vnd.github+json")
                .connectTimeout(TIMEOUT_MS)
                .readTimeout(TIMEOUT_MS)
                .tuner { connection -> token?.let { connection.setRequestProperty("Authorization", "Bearer $it") } }
                .connect { request ->
                    (request.connection as? HttpURLConnection)
                        ?.getHeaderField("X-RateLimit-Remaining")
                        ?.toIntOrNull()
                        ?.let { if (it <= 0) apiRateLimited = true }
                    parseCommitAuthorAvatar(request.reader.readText())
                }
        } catch (_: IOException) {
            null
        } catch (e: RuntimeException) {
            thisLogger().debug("GitHub commit avatar lookup failed", e)
            null
        }

    private fun gitHubToken(): String? {
        if (tokenResolved) return cachedToken
        synchronized(tokenLock) {
            if (tokenResolved) return cachedToken
            cachedToken = runCatching { readGitCredentialToken() }.getOrNull()
            tokenResolved = true
        }
        return cachedToken
    }

    private fun readGitCredentialToken(): String? {
        val gitPath = runCatching { GitExecutableManager.getInstance().getPathToGit() }.getOrNull() ?: return null
        val command =
            GeneralCommandLine(gitPath, "credential", "fill")
                .withEnvironment("GIT_TERMINAL_PROMPT", "0")
        val process = command.createProcess()
        process.outputStream.bufferedWriter().use { it.write("protocol=https\nhost=github.com\n\n") }
        val output = CapturingProcessHandler(process, Charsets.UTF_8, command.commandLineString).runProcess(CREDENTIAL_TIMEOUT_MS)
        if (output.isTimeout || output.exitCode != 0) return null
        return output.stdout
            .lineSequence()
            .firstOrNull { it.startsWith("password=") }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)
    }

    private fun gravatarUrl(email: String): String {
        val hash =
            MessageDigest
                .getInstance("MD5")
                .digest(email.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return "https://www.gravatar.com/avatar/$hash?s=$REQUEST_SIZE&d=404"
    }

    companion object {
        private const val REQUEST_SIZE = 64
        private const val TIMEOUT_MS = 4000
        private const val CREDENTIAL_TIMEOUT_MS = 4000

        fun getInstance(): AvatarService = service()
    }
}

/** `12345+login@users.noreply.github.com` → avatar by user id; `login@users.noreply.github.com` → by login. */
internal fun gitHubNoReplyAvatarUrl(email: String): String? {
    NOREPLY_WITH_ID.matchEntire(email)?.let { return "https://avatars.githubusercontent.com/u/${it.groupValues[1]}?s=64" }
    val login = NOREPLY_LOGIN.matchEntire(email)?.groupValues?.get(1) ?: return null
    return "https://github.com/$login.png?size=64"
}

/** Parses an `owner/repo` from a `github.com` remote URL (https or ssh); `null` for other hosts. */
internal fun parseGitHubRepo(remoteUrl: String): GitHubRepo? {
    var rest = remoteUrl.trim().removeSuffix(".git")
    if ("://" in rest) rest = rest.substringAfter("://")
    rest = rest.substringAfter('@')
    val host = rest.takeWhile { it != '/' && it != ':' }
    if (!host.equals("github.com", ignoreCase = true)) return null
    val path = rest.substring(host.length).trimStart(':', '/').trim('/')
    val parts = path.split('/').filter(String::isNotBlank)
    if (parts.size < 2) return null
    return GitHubRepo(parts[parts.size - 2], parts.last())
}

/** Extracts `author.avatar_url` from the first element of a GitHub commits-API JSON array. */
internal fun parseCommitAuthorAvatar(json: String): String? =
    runCatching {
        val array = JsonParser.parseString(json) as? JsonArray ?: return null
        val first = array.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val author = first.get("author")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        author.get("avatar_url")?.takeIf { it.isJsonPrimitive }?.asString
    }.getOrNull()

private val NOREPLY_WITH_ID = Regex("""(\d+)\+[^@]+@users\.noreply\.github\.com""")
private val NOREPLY_LOGIN = Regex("""([^@+]+)@users\.noreply\.github\.com""")
