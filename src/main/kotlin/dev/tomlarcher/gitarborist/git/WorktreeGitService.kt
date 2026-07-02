package dev.tomlarcher.gitarborist.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.tomlarcher.gitarborist.util.PathUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Project service owning every Git4Idea-backed worktree operation: list, add, remove, lock, unlock,
 * prune, repair, and move, plus removal preflight and status loading. Listing is scoped to the
 * project's owning Git root and deduplicated by canonical main worktree so nested dependency
 * repositories never appear. Every public method runs Git off the EDT and refreshes the repository
 * model and VFS after a mutating command succeeds.
 */
@Service(Service.Level.PROJECT)
class WorktreeGitService(
    private val project: Project,
) {
    private val parser = WorktreePorcelainParser()
    private val statusLoader = WorktreeStatusLoader(project)

    fun listWorktrees(): List<WorktreeInfo> {
        assertNotEdt()
        val seenFamilies = linkedSetOf<Path>()
        return repositoryRootsForList()
            .flatMap { root ->
                val rootPath = root.toNioPath()
                val handler = GitLineHandler(project, root, GitCommand.WORKTREE)
                handler.addParameters("list", "--porcelain")
                val result = Git.getInstance().runCommand(handler)
                if (!result.success()) {
                    emptyList()
                } else {
                    val output = result.stdout()
                    val mainRoot = parser.mainWorktreeRoot(output)?.let(PathUtil::normalize) ?: rootPath
                    if (seenFamilies.add(mainRoot)) parser.parse(mainRoot, output, rootPath) else emptyList()
                }
            }.distinctBy { PathUtil.normalize(it.path) }
    }

    fun addWorktree(request: AddWorktreeRequest): WorktreeCommandResult {
        assertNotEdt()
        require(!request.targetPath.exists()) { "Target path already exists: ${request.targetPath}" }
        request.targetPath.parent?.createDirectories()
        val registered = registeredRepository(request.repositoryRoot)
        val rootFile = registered?.root ?: repositoryRootFile(request.repositoryRoot)
        val handler = GitLineHandler(project, rootFile, GitCommand.WORKTREE)
        handler.addParameters("add")
        if (request.detach) handler.addParameters("--detach")
        if (request.createBranch && request.branchName != null) handler.addParameters("-b", request.branchName)
        handler.endOptions()
        handler.addParameters(request.targetPath.toString(), request.sourceRef)
        val result = Git.getInstance().runCommand(handler).toWorktreeResult()
        if (result.success) {
            registered?.update()
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(request.targetPath)
        }
        return result
    }

    /**
     * Remote-tracking branches known to the git root from its last fetch, ordered for the picker. Reads
     * `git for-each-ref refs/remotes` on the resolved root so it works before Git4Idea has registered a
     * repository for the project (as the worktree list already does); call [fetchRemotes] first to
     * surface branches added on the remote since the last fetch.
     */
    fun listRemoteBranches(repositoryRoot: Path): List<RemoteBranch> {
        assertNotEdt()
        val rootFile = repositoryRootFile(repositoryRoot)
        val handler = GitLineHandler(project, rootFile, GitCommand.FOR_EACH_REF)
        handler.addParameters("--format=%(refname)%00%(objectname:short)", "refs/remotes")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return RemoteWorktreePlanner.sorted(RemoteBranchParser.parse(result.stdout(), remoteNames(rootFile)))
    }

    /**
     * Fetches every remote so a following [listRemoteBranches] reflects branches added on the remote.
     * When Git4Idea has a registered repository it uses the fetch support that wires up authentication
     * and progress; otherwise it falls back to `git fetch --all --prune` on the resolved root so the
     * flow still works before the repository is registered.
     */
    fun fetchRemotes(repositoryRoot: Path): WorktreeCommandResult {
        assertNotEdt()
        val registered = registeredRepository(repositoryRoot)
        if (registered != null) {
            val result = GitFetchSupport.fetchSupport(project).fetchAllRemotes(listOf(registered))
            return runCatching { result.throwExceptionIfFailed() }.fold(
                onSuccess = {
                    registered.update()
                    WorktreeCommandResult(success = true, stdout = "Fetched all remotes", stderr = "")
                },
                onFailure = { error ->
                    WorktreeCommandResult(success = false, stdout = "", stderr = error.message ?: "Fetch failed")
                },
            )
        }
        val handler = GitLineHandler(project, repositoryRootFile(repositoryRoot), GitCommand.FETCH)
        handler.addParameters("--all", "--prune")
        return Git.getInstance().runCommand(handler).toWorktreeResult()
    }

    fun removeWorktree(request: RemoveWorktreeRequest): WorktreeCommandResult {
        assertNotEdt()
        require(!request.worktree.isMain) { "Refusing to remove main worktree" }
        val repository = repositoryFor(request.worktree.repositoryRoot)
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("remove")
        if (request.force) handler.addParameters("--force")
        handler.endOptions()
        handler.addParameters(request.worktree.path.toString())
        val result = Git.getInstance().runCommand(handler).toWorktreeResult()
        if (result.success && request.deleteBranch) {
            deleteLocalBranch(repository, request.worktree.branch)
        }
        if (result.success) refresh(repository, request.worktree.path)
        return result
    }

    fun lockWorktree(
        worktree: WorktreeInfo,
        reason: String?,
    ): WorktreeCommandResult {
        assertNotEdt()
        val repository = repositoryFor(worktree.repositoryRoot)
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("lock")
        if (!reason.isNullOrBlank()) handler.addParameters("--reason", reason)
        handler.endOptions()
        handler.addParameters(worktree.path.toString())
        return Git.getInstance().runCommand(handler).toWorktreeResult().also {
            if (it.success) refresh(repository, worktree.path)
        }
    }

    fun unlockWorktree(worktree: WorktreeInfo): WorktreeCommandResult {
        assertNotEdt()
        val repository = repositoryFor(worktree.repositoryRoot)
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("unlock")
        handler.endOptions()
        handler.addParameters(worktree.path.toString())
        return Git.getInstance().runCommand(handler).toWorktreeResult().also {
            if (it.success) refresh(repository, worktree.path)
        }
    }

    fun prune(repositoryRoot: Path): WorktreeCommandResult {
        assertNotEdt()
        val repository = repositoryFor(repositoryRoot)
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("prune")
        return Git.getInstance().runCommand(handler).toWorktreeResult().also {
            if (it.success) refresh(repository, repositoryRoot)
        }
    }

    fun repair(repositoryRoot: Path): WorktreeCommandResult {
        assertNotEdt()
        val repository = repositoryFor(repositoryRoot)
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("repair")
        return Git.getInstance().runCommand(handler).toWorktreeResult().also {
            if (it.success) refresh(repository, repositoryRoot)
        }
    }

    fun move(
        worktree: WorktreeInfo,
        newPath: Path,
    ): WorktreeCommandResult {
        assertNotEdt()
        require(!newPath.exists()) { "Target path already exists: $newPath" }
        newPath.parent?.createDirectories()
        val repository = repositoryFor(worktree.repositoryRoot)
        val handler = GitLineHandler(project, repository.root, GitCommand.WORKTREE)
        handler.addParameters("move")
        handler.endOptions()
        handler.addParameters(worktree.path.toString(), newPath.toString())
        return Git.getInstance().runCommand(handler).toWorktreeResult().also {
            if (it.success) refresh(repository, newPath)
        }
    }

    fun removalPreflight(worktree: WorktreeInfo): RemovalPreflight {
        assertNotEdt()
        if (worktree.isMain) return RemovalPreflight(refuse = true, dirty = false, unpushed = false, locked = worktree.isLocked)
        val status = status(worktree.path)
        val unpushed = status.ahead?.let { it > 0 } ?: false
        return RemovalPreflight(refuse = false, dirty = status.dirty, unpushed = unpushed, locked = worktree.isLocked)
    }

    fun status(
        path: Path,
        defaultBranch: String? = null,
        branchName: String? = null,
    ): WorktreeStatus {
        assertNotEdt()
        return statusLoader.load(path, defaultBranch, branchName)
    }

    /**
     * Relative paths git considers ignored under [root], used by the all-ignored carry-over scope.
     * Uses `--directory` so fully-ignored directories collapse to a single `dir/` entry instead of
     * enumerating every file, and `-z` so newlines in paths are not ambiguous.
     */
    fun listIgnoredPaths(root: Path): List<Path> {
        assertNotEdt()
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return emptyList()
        val handler = GitLineHandler(project, virtualFile, GitCommand.LS_FILES)
        handler.addParameters("--others", "--ignored", "--exclude-standard", "--directory", "-z")
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return result
            .getOutputAsJoinedString()
            .split('\u0000')
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
    }

    private fun assertNotEdt() {
        check(!ApplicationManager.getApplication().isDispatchThread) {
            "WorktreeGitService must not execute Git commands on the EDT"
        }
    }

    private fun deleteLocalBranch(
        repository: GitRepository,
        branch: String?,
    ) {
        val name = branch?.takeIf(::isDeletableLocalBranch) ?: return
        val handler = GitLineHandler(project, repository.root, GitCommand.BRANCH)
        handler.addParameters("-D")
        handler.endOptions()
        handler.addParameters(name)
        Git.getInstance().runCommand(handler)
    }

    private fun repositoryRootsForList(): List<VirtualFile> {
        val fallbackRootPath = projectBaseGitRootPath() ?: fallbackGitRootPath()
        val fallbackRoot = fallbackRootPath?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        if (fallbackRoot != null) return listOf(fallbackRoot)
        return repositories().map { it.root }
    }

    private fun repositories(): List<GitRepository> {
        val allRepositories = GitRepositoryManager.getInstance(project).repositories
        val scopedRoots = scopedRepositoryRoots(allRepositories)
        return if (scopedRoots.isEmpty()) {
            allRepositories
        } else {
            allRepositories.filter { repository ->
                PathUtil.normalize(repository.root.toNioPath()) in scopedRoots
            }
        }
    }

    private fun scopedRepositoryRoots(allRepositories: List<GitRepository>): Set<Path> =
        RepositoryScope.chooseProjectRoots(
            projectBasePath = project.basePath?.let(Path::of),
            projectBaseVcsRoot = projectBaseGitRootPath() ?: fallbackGitRootPath(),
            repositoryRoots = allRepositories.map { it.root.toNioPath() },
        )

    private fun fallbackGitRootPath(): Path? {
        var candidate = project.basePath?.let(Path::of)?.let(PathUtil::normalize)
        while (candidate != null) {
            val gitPath = candidate.resolve(".git")
            if (gitPath.isDirectory() || gitPath.isRegularFile()) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun projectBaseGitRootPath(): Path? =
        project.basePath
            ?.let(Path::of)
            ?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
            ?.let { ProjectLevelVcsManager.getInstance(project).getVcsRootFor(it) }
            ?.toNioPath()
            ?.let(PathUtil::normalize)

    private fun repositoryFor(root: Path): GitRepository =
        repositories().firstOrNull { repository ->
            PathUtil.samePath(repository.root.toNioPath(), root)
        } ?: error("No Git repository for $root")

    /** The registered repository whose root matches [root], or null when Git4Idea has not registered one. */
    private fun registeredRepository(root: Path): GitRepository? =
        repositories().firstOrNull { repository ->
            PathUtil.samePath(repository.root.toNioPath(), root)
        }

    /**
     * A VFS handle for the git root at [root]: the registered repository's root when Git4Idea has one,
     * otherwise the on-disk directory (which the worktree listing has already confirmed is a git root),
     * so remote-branch and worktree-add commands run even before the repository is registered.
     */
    private fun repositoryRootFile(root: Path): VirtualFile =
        registeredRepository(root)?.root
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
            ?: error("No Git repository for $root")

    /** Configured remote names for the git root, used to split `<remote>/<branch>` tracking refs. */
    private fun remoteNames(rootFile: VirtualFile): List<String> {
        val handler = GitLineHandler(project, rootFile, GitCommand.REMOTE)
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return result
            .stdout()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
    }

    private fun refresh(
        repository: GitRepository,
        path: Path,
    ) {
        repository.update()
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }
}

/** Outcome of the pre-removal safety check surfaced in the remove dialog. */
data class RemovalPreflight(
    val refuse: Boolean,
    val dirty: Boolean,
    val unpushed: Boolean,
    val locked: Boolean,
)

/**
 * A worktree-backing branch is a local branch (`refs/heads/...`), which git can delete by its bare
 * name even when it contains slashes (e.g. `feature/demo`). Only blank/absent names are skipped.
 */
internal fun isDeletableLocalBranch(branch: String): Boolean = branch.isNotBlank()

private fun git4idea.commands.GitCommandResult.stdout(): String = output.joinToString("\n")

private fun git4idea.commands.GitCommandResult.stderr(): String = errorOutput.joinToString("\n")

private fun git4idea.commands.GitCommandResult.toWorktreeResult(): WorktreeCommandResult =
    WorktreeCommandResult(
        success = success(),
        stdout = stdout(),
        stderr = stderr(),
    )
