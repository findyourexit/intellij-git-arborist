package dev.tomlarcher.gitarborist.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.content.ContentFactory
import dev.tomlarcher.gitarborist.git.RepositoryScope
import dev.tomlarcher.gitarborist.util.PathUtil
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Registers the Git Arborist tool window: hosts the [WorktreesPanel], installs the top-right
 * title actions (Create, Remove, Switch, Open, Refresh), and titles the window from the owning
 * repository identity, preferring the remote `owner/repo` over the local root or project name.
 */
class WorktreesToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val viewModel = WorktreesViewModel(project)
        lateinit var content: com.intellij.ui.content.Content
        lateinit var panel: WorktreesPanel

        fun updateTitle() = updateRepositoryTitle(project, toolWindow, content)
        panel = WorktreesPanel(project, viewModel, toolWindow.disposable, ::updateTitle)
        content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")
        updateTitle()
        toolWindow.setStripeTitle("Git Arborist")
        toolWindow.setTitleActions(
            listOf(
                titleAction("Create Worktree", "Create worktree", AllIcons.General.Add) { panel.createWorktree() },
                titleAction("New Worktree from Remote Branch", "Create a worktree from a remote branch", AllIcons.Vcs.Branch) {
                    panel.checkoutRemoteBranch()
                },
                titleAction("Remove Worktree", "Remove selected worktree", AllIcons.General.Remove) { panel.removeSelected() },
                titleAction("Open Worktree...", "Open selected worktree", AllIcons.Actions.MenuOpen) { panel.openSelected() },
                titleAction("Refresh Worktrees", "Refresh worktrees", AllIcons.Actions.Refresh) { panel.refresh() },
            ),
        )
        toolWindow.contentManager.addContent(content)
        refreshWhenShown(project, toolWindow, panel)
    }

    /**
     * Silently refreshes the panel whenever the tool window becomes visible, so worktrees changed
     * outside Git Arborist while it was hidden are picked up on next show without a manual refresh.
     */
    private fun refreshWhenShown(
        project: Project,
        toolWindow: ToolWindow,
        panel: WorktreesPanel,
    ) {
        var wasVisible = toolWindow.isVisible
        project.messageBus
            .connect(toolWindow.disposable)
            .subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun stateChanged(toolWindowManager: ToolWindowManager) {
                        val visible = toolWindow.isVisible
                        if (visible && !wasVisible) panel.refresh()
                        wasVisible = visible
                    }
                },
            )
    }

    private fun updateRepositoryTitle(
        project: Project,
        toolWindow: ToolWindow,
        content: com.intellij.ui.content.Content,
    ) {
        val title = repositoryDisplayName(project)
        toolWindow.setTitle(title)
        content.setToolwindowTitle(title)
        content.description = title
    }

    private fun repositoryDisplayName(project: Project): String {
        val repository = scopedRepository(project)
        val root = repository?.root?.toNioPath() ?: fallbackGitRootPath(project)
        val remoteUrl = repository?.let(::repoRemoteUrl) ?: root?.let(::gitConfigRemoteUrl)
        remoteUrl?.let(::remoteDisplayName)?.let { return it }
        return root?.fileName?.toString() ?: project.name
    }

    private fun repoRemoteUrl(repository: GitRepository): String? =
        repository.remotes.firstOrNull { it.name == GitRemote.ORIGIN }?.firstUrl
            ?: repository.remotes.firstOrNull()?.firstUrl

    /**
     * Reads the `origin` (or first) remote URL straight from `.git/config`, so the title resolves to
     * `owner/repo` even before Git4Idea has discovered/scoped the repository.
     */
    private fun gitConfigRemoteUrl(root: Path): String? {
        val configFile =
            root
                .resolve(".git")
                .takeIf { it.isDirectory() }
                ?.resolve("config")
                ?.takeIf { it.isRegularFile() }
                ?: return null
        return runCatching { configFile.readText() }.getOrNull()?.let(::parseGitConfigRemoteUrl)
    }

    private fun scopedRepository(project: Project) =
        GitRepositoryManager.getInstance(project).repositories.let { repositories ->
            val scopedRoots =
                RepositoryScope.chooseProjectRoots(
                    projectBasePath = project.basePath?.let(Path::of),
                    projectBaseVcsRoot = projectBaseGitRootPath(project) ?: fallbackGitRootPath(project),
                    repositoryRoots = repositories.map { it.root.toNioPath() },
                )
            repositories.firstOrNull { repository ->
                PathUtil.normalize(repository.root.toNioPath()) in scopedRoots
            } ?: repositories.firstOrNull { repository ->
                fallbackGitRootPath(project)?.let { PathUtil.samePath(repository.root.toNioPath(), it) } == true
            }
        }

    private fun projectBaseGitRootPath(project: Project): Path? =
        project.basePath
            ?.let(Path::of)
            ?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
            ?.let { ProjectLevelVcsManager.getInstance(project).getVcsRootFor(it) }
            ?.toNioPath()
            ?.let(PathUtil::normalize)

    private fun fallbackGitRootPath(project: Project): Path? {
        var candidate = project.basePath?.let(Path::of)?.let(PathUtil::normalize)
        while (candidate != null) {
            val gitPath = candidate.resolve(".git")
            if (gitPath.isDirectory() || gitPath.isRegularFile()) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun titleAction(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        action: () -> Unit,
    ): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(event: AnActionEvent) {
                action()
            }
        }
}

internal fun remoteDisplayName(remoteUrl: String): String? {
    val withoutSuffix = remoteUrl.removeSuffix(".git")
    val path =
        when {
            "://" in withoutSuffix -> withoutSuffix.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
            ':' in withoutSuffix -> withoutSuffix.substringAfter(':')
            else -> withoutSuffix
        }
    val parts = path.trim('/').split('/').filter(String::isNotBlank)
    if (parts.size < 2) return null
    return "${parts[parts.lastIndex - 1]}/${parts.last()}"
}

/**
 * Extracts the `origin` remote URL (or the first remote's URL) from raw `.git/config` text without
 * relying on Git4Idea's repository model. Returns null when no remote section has a URL.
 */
internal fun parseGitConfigRemoteUrl(config: String): String? {
    val urls = linkedMapOf<String, String>()
    var currentRemote: String? = null
    config.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("[")) {
            currentRemote = REMOTE_SECTION.find(line)?.groupValues?.get(1)
            return@forEach
        }
        val remote = currentRemote ?: return@forEach
        if (line.substringBefore('=').trim() == "url") {
            val url = line.substringAfter('=').trim()
            if (url.isNotEmpty()) urls.putIfAbsent(remote, url)
        }
    }
    return urls["origin"] ?: urls.values.firstOrNull()
}

private val REMOTE_SECTION = Regex("""^\[remote "([^"]+)"]""")
