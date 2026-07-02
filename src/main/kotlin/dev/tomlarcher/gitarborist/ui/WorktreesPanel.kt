package dev.tomlarcher.gitarborist.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.BadgeIconSupplier
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.Alarm
import com.intellij.util.ui.FilterComponent
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tomlarcher.gitarborist.carry.WorktreeCarryOverService
import dev.tomlarcher.gitarborist.git.AddWorktreeRequest
import dev.tomlarcher.gitarborist.git.RemoveWorktreeRequest
import dev.tomlarcher.gitarborist.git.WorktreeCacheService
import dev.tomlarcher.gitarborist.git.WorktreeCommandResult
import dev.tomlarcher.gitarborist.git.WorktreeGitService
import dev.tomlarcher.gitarborist.git.WorktreeInfo
import dev.tomlarcher.gitarborist.git.requiresForceRetry
import dev.tomlarcher.gitarborist.open.WorktreeOpenService
import dev.tomlarcher.gitarborist.settings.GitArboristSettingsResolver
import dev.tomlarcher.gitarborist.util.Notifications
import dev.tomlarcher.gitarborist.util.PathUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.function.Supplier
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.DocumentEvent

class WorktreesPanel(
    private val project: Project,
    private val viewModel: WorktreesViewModel,
    parentDisposable: Disposable,
    private val onRendered: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val listModel = DefaultListModel<WorktreeRow>()
    private val list =
        object : JBList<WorktreeRow>(listModel) {
            override fun getToolTipText(event: MouseEvent): String? {
                val index = locationToIndex(event.point)
                val bounds = if (index >= 0) getCellBounds(index, index) else null
                val row = if (bounds?.contains(event.point) == true) model.getElementAt(index) else null
                return if (row == null || bounds == null) {
                    null
                } else {
                    worktreeTooltip(row, event.x - bounds.x, event.y - bounds.y, bounds.width, bounds.height)
                }
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = WorktreeListCellRenderer()
            setExpandableItemsEnabled(false)
            ToolTipManager.sharedInstance().registerComponent(this)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(event: MouseEvent) {
                        if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) {
                            openSelected()
                        }
                    }
                },
            )
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(event: KeyEvent) {
                        if (event.keyCode == KeyEvent.VK_ENTER) {
                            event.consume()
                            openSelected()
                        }
                    }
                },
            )
        }
    private val searchHistory = mutableListOf<String>()
    private var emptyRefreshRetries = 0
    private val externalChangeAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)
    private val searchField =
        ExtendableTextField().apply {
            emptyText.text = "Search branch, path, status, commit, message"
            addActionListener {
                recordSearchQuery(text)
                applyFilters()
            }
            addExtension(
                object : ExtendableTextComponent.Extension {
                    override fun isIconBeforeText(): Boolean = true

                    override fun getIcon(hovered: Boolean): Icon = AllIcons.Actions.SearchWithHistory

                    override fun getActionOnClick(): Runnable = Runnable { showSearchHistoryPopup() }
                },
            )
        }
    private var quickFilter = WorktreeQuickFilter.All
    private var stateFilter: WorktreeStateFilter? = null
    private var creatorFilter: String? = null
    private var sortMode: WorktreeSortMode? = null
    private var allRows: List<WorktreeRow> = emptyList()
    private val quickFilterComponent = createQuickFilterButton()
    private val stateFilterComponent =
        SelectFilterComponent(
            "State",
            { WorktreeStateFilter.entries.toList() },
            { filter ->
                stateFilter = filter
                if (filter != null) quickFilter = WorktreeQuickFilter.All
                applyFilters()
            },
            WorktreeStateFilter::label,
        )
    private val creatorFilterComponent =
        SelectFilterComponent(
            "Creator",
            {
                allRows
                    .mapNotNull { it.creator.takeIf(String::isNotBlank) }
                    .distinct()
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
            },
            { creator ->
                creatorFilter = creator
                if (creator != null) quickFilter = WorktreeQuickFilter.All
                applyFilters()
            },
            { it },
            { creator -> CreatorAvatarIcon(creator) },
        )
    private val sortModeComponent =
        SelectFilterComponent(
            "Sort",
            { WorktreeSortMode.entries.toList() },
            { mode ->
                sortMode = mode
                applyFilters()
            },
            WorktreeSortMode::label,
        )

    init {
        add(header(), BorderLayout.NORTH)
        add(
            JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                viewport.isOpaque = false
                isOpaque = false
            },
            BorderLayout.CENTER,
        )
        WorktreesContextMenu(
            open = ::openSelected,
            lock = ::lockSelected,
            unlock = ::unlockSelected,
            move = ::moveSelected,
            reapplyCarryOver = ::reapplyCarryOverSelected,
            remove = ::removeSelected,
            selectedIsLocked = { list.selectedValue?.info?.isLocked },
        ).installOn(list)
        installExternalChangeListener(parentDisposable)
        refresh()
    }

    private fun header(): JPanel =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 0, 10)
            add(searchAndFilters(), BorderLayout.CENTER)
        }

    private fun searchAndFilters(): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            searchField.document.addDocumentListener(
                object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        applyFilters()
                    }
                },
            )
            add(searchField, BorderLayout.NORTH)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    border = JBUI.Borders.emptyTop(10)
                    isOpaque = false
                    add(quickFilterComponent)
                    add(stateFilterComponent)
                    add(creatorFilterComponent)
                    add(sortModeComponent)
                },
                BorderLayout.SOUTH,
            )
        }

    private fun createQuickFilterButton(): JComponent {
        val toolbar =
            ActionManager.getInstance().createActionToolbar(
                "Worktrees.FilterToolbar",
                DefaultActionGroup(QuickFilterPopupAction()),
                true,
            )
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()
        toolbar.targetComponent = this
        return toolbar.component
    }

    private fun showQuickFiltersPopup(parentComponent: JComponent) {
        SearchableChooserPopup.show(
            anchor = parentComponent,
            items = WorktreeQuickFilter.entries.toList(),
            selectedItem = quickFilter,
            emptyText = "No quick filters",
            labelProvider = WorktreeQuickFilter::label,
            iconProvider = { null },
            onSelected = ::selectQuickFilter,
        )
    }

    private fun selectQuickFilter(filter: WorktreeQuickFilter) {
        quickFilter = filter
        stateFilter = null
        creatorFilter = null
        sortMode = null
        stateFilterComponent.clearSelection()
        creatorFilterComponent.clearSelection()
        sortModeComponent.clearSelection()
        applyFilters()
    }

    private inner class QuickFilterPopupAction : DumbAwareAction("Quick Filters", "Quick Filters", QUICK_FILTER_ICON.originalIcon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.icon = QUICK_FILTER_ICON.getLiveIndicatorIcon(quickFilter != WorktreeQuickFilter.All)
        }

        override fun actionPerformed(event: AnActionEvent) {
            showQuickFiltersPopup(event.inputEvent?.component as? JComponent ?: this@WorktreesPanel)
        }
    }

    private fun showSearchHistoryPopup() {
        recordSearchQuery(searchField.text)
        SearchableChooserPopup.show(
            anchor = searchField,
            items = searchHistory.ifEmpty { listOf(NO_SEARCH_HISTORY_ITEM) },
            selectedItem = null,
            emptyText = NO_SEARCH_HISTORY_ITEM,
            labelProvider = { it },
            iconProvider = { null },
            onSelected = { value ->
                if (value != NO_SEARCH_HISTORY_ITEM) {
                    searchField.text = value
                    applyFilters()
                }
            },
        )
    }

    private fun recordSearchQuery(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        searchHistory.remove(normalized)
        searchHistory.add(0, normalized)
        if (searchHistory.size > SEARCH_HISTORY_LIMIT) {
            searchHistory.removeAt(searchHistory.lastIndex)
        }
    }

    fun refresh() {
        viewModel.refresh {
            SwingUtilities.invokeLater { render(viewModel.state) }
        }
    }

    /**
     * Subscribes to Git4Idea repository changes so worktrees added, removed, or moved outside this
     * tool window (the Git CLI, other plugins) update the list without a manual refresh. Bursts of
     * change events are coalesced, and only refreshed while the panel is showing to avoid background
     * Git churn for a hidden tool window; changes made while hidden are picked up on next show.
     */
    private fun installExternalChangeListener(parentDisposable: Disposable) {
        project.messageBus
            .connect(parentDisposable)
            .subscribe(
                GitRepository.GIT_REPO_CHANGE,
                GitRepositoryChangeListener { if (isShowing) refreshSilently() },
            )
    }

    /** Coalesces rapid external change events into a single deferred refresh. */
    fun refreshSilently() {
        externalChangeAlarm.cancelAllRequests()
        externalChangeAlarm.addRequest({ refresh() }, EXTERNAL_REFRESH_DELAY_MS)
    }

    private fun render(state: WorktreesUiState) {
        list.emptyText.clear()
        allRows = if (state.loading || state.error != null) emptyList() else state.rows
        when {
            state.loading -> list.emptyText.appendText("Loading worktrees...")
            state.error != null -> list.emptyText.appendText("Unable to load worktrees: ${state.error}")
            state.rows.isEmpty() -> {
                list.emptyText.appendText("No Git worktrees found")
                if (emptyRefreshRetries < INITIAL_EMPTY_REFRESH_RETRIES) {
                    emptyRefreshRetries += 1
                    Timer(INITIAL_EMPTY_REFRESH_DELAY_MS) { refresh() }
                        .apply { isRepeats = false }
                        .start()
                }
            }
            else -> emptyRefreshRetries = 0
        }
        applyFilters()
    }

    private fun applyFilters() {
        val selectedPath = list.selectedValue?.info?.path
        val tokens =
            searchField.text
                .trim()
                .lowercase()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        val filteredRows =
            allRows
                .asSequence()
                .filter { quickFilter.matches(it) }
                .filter { row -> stateFilter?.matches(row) ?: true }
                .filter { row -> creatorFilter?.let { it == row.creator } ?: true }
                .filter { rowMatchesFilter(rowSearchValues(it), tokens) }
                .let { rows -> sortMode?.sort(rows) ?: rows.toList() }

        listModel.clear()
        filteredRows.forEach(listModel::addElement)
        if (listModel.isEmpty && allRows.isNotEmpty()) {
            list.emptyText.clear()
            list.emptyText.appendText("No matching worktrees")
        }
        val selectedIndex = filteredRows.indexOfFirst { selectedPath != null && PathUtil.samePath(it.info.path, selectedPath) }
        onRendered()
        when {
            selectedIndex >= 0 -> list.selectedIndex = selectedIndex
            !listModel.isEmpty -> list.selectedIndex = 0
        }
    }

    fun createWorktree() {
        val repositoryRoot = mainRepositoryRoot() ?: return
        val settings = GitArboristSettingsResolver.effective(project)
        val dialog = CreateWorktreeDialog(project, repositoryRoot, settings.openAfterCreate, settings.defaultWorktreeDirectory)
        if (!dialog.showAndGet()) return
        createWorktree(dialog.request(), dialog.shouldOpenAfterCreate)
    }

    fun checkoutRemoteBranch() {
        val repositoryRoot = mainRepositoryRoot() ?: return
        val settings = GitArboristSettingsResolver.effective(project)
        runBackground(
            title = "Loading remote branches",
            work = { project.service<WorktreeGitService>().listRemoteBranches(repositoryRoot) },
        ) { branches ->
            val dialog =
                CheckoutRemoteBranchDialog(
                    project = project,
                    repositoryRoot = repositoryRoot,
                    initialBranches = branches,
                    openByDefault = settings.openAfterCreate,
                    worktreeDirectory = settings.defaultWorktreeDirectory,
                    fetcher = {
                        val service = project.service<WorktreeGitService>()
                        val fetch = service.fetchRemotes(repositoryRoot)
                        if (!fetch.success) error(fetch.output.ifBlank { "Fetch failed" })
                        service.listRemoteBranches(repositoryRoot)
                    },
                )
            if (dialog.showAndGet()) createWorktree(dialog.request(), dialog.shouldOpenAfterCreate)
        }
    }

    private fun mainRepositoryRoot(): Path? {
        val main =
            viewModel.state.rows
                .firstOrNull { it.info.isMain }
                ?.info
                ?: viewModel.state.rows
                    .firstOrNull()
                    ?.info
        return main?.repositoryRoot ?: project.basePath?.let(Path::of)
    }

    private fun createWorktree(
        request: AddWorktreeRequest,
        openAfterCreate: Boolean,
    ) {
        runBackground(
            title = "Creating Git worktree",
            work = {
                val result = project.service<WorktreeGitService>().addWorktree(request)
                val created =
                    if (result.success && openAfterCreate) {
                        project
                            .service<WorktreeCacheService>()
                            .refreshBlocking()
                            .worktrees
                            .firstOrNull { PathUtil.samePath(it.path, request.targetPath) }
                    } else {
                        null
                    }
                CreateResult(result, created)
            },
        ) { result ->
            if (result.command.success) {
                Notifications.info(project, "Worktree created", request.targetPath.toString())
                refresh()
                result.worktree?.let { worktree ->
                    if (openAfterCreate) project.service<WorktreeOpenService>().openWorktreeAsync(worktree)
                }
            } else {
                Notifications.error(project, "Unable to create worktree", result.command.output.ifBlank { "Git command failed" })
            }
        }
    }

    fun removeSelected() {
        val worktree = selectedWorktree() ?: return
        runBackground(
            title = "Checking Git worktree",
            work = { project.service<WorktreeGitService>().removalPreflight(worktree) },
        ) { preflight ->
            if (preflight.refuse) {
                Messages.showErrorDialog(project, "The main worktree cannot be removed.", "Remove Worktree")
                return@runBackground
            }
            val dialog = RemoveWorktreeDialog(project, worktree, preflight)
            if (dialog.showAndGet()) removeWorktree(worktree, dialog.force, dialog.deleteBranch)
        }
    }

    private fun removeWorktree(
        worktree: WorktreeInfo,
        force: Boolean,
        deleteBranch: Boolean = false,
    ) {
        runBackground(
            title = if (force) "Force removing Git worktree" else "Removing Git worktree",
            work = {
                project.service<WorktreeGitService>().removeWorktree(RemoveWorktreeRequest(worktree, force = force, deleteBranch = deleteBranch)).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            },
        ) { result ->
            when {
                result.success -> {
                    Notifications.info(project, "Worktree removed", worktree.path.toString())
                    refresh()
                }
                !force && result.requiresForceRetry() -> retryRemoveWithForce(worktree, result, deleteBranch)
                else -> Notifications.error(project, "Remove failed", result.output.ifBlank { "Git command failed" })
            }
        }
    }

    private fun retryRemoveWithForce(
        worktree: WorktreeInfo,
        result: WorktreeCommandResult,
        deleteBranch: Boolean,
    ) {
        val message =
            buildString {
                append("Git refused to remove this worktree without --force.\n\n")
                append(result.output.ifBlank { "The worktree may contain dirty, ignored, or generated files." })
                append("\n\nRetry with force?")
            }
        if (Messages.showYesNoDialog(project, message, "Force Remove Worktree", null) == Messages.YES) {
            removeWorktree(worktree, force = true, deleteBranch = deleteBranch)
        }
    }

    fun openSelected() {
        val worktree = selectedWorktree() ?: return
        project.service<WorktreeOpenService>().openWorktreeAsync(worktree)
    }

    private fun selectedWorktree(): WorktreeInfo? {
        val worktree = list.selectedValue?.info
        if (worktree == null) {
            Messages.showInfoMessage(project, "Select a worktree first.", "Git Arborist")
        }
        return worktree
    }

    private fun lockSelected() {
        val worktree = selectedWorktree() ?: return
        val reason = Messages.showInputDialog(project, "Lock reason (optional):", "Lock Worktree", null) ?: return
        runBackground(
            title = "Locking Git worktree",
            work = {
                project.service<WorktreeGitService>().lockWorktree(worktree, reason.ifBlank { null }).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            },
        ) { result -> notifyResult(result, "Worktree locked") }
    }

    private fun unlockSelected() {
        val worktree = selectedWorktree() ?: return
        runBackground(
            title = "Unlocking Git worktree",
            work = {
                project.service<WorktreeGitService>().unlockWorktree(worktree).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            },
        ) { result -> notifyResult(result, "Worktree unlocked") }
    }

    private fun moveSelected() {
        val worktree = selectedWorktree() ?: return
        val input =
            Messages
                .showInputDialog(project, "New worktree path:", "Move Worktree", null, worktree.path.toString(), null)
                ?.trim()
                .orEmpty()
        if (input.isEmpty()) return
        val newPath = Path.of(input)
        runBackground(
            title = "Moving Git worktree",
            work = {
                project.service<WorktreeGitService>().move(worktree, newPath).also {
                    if (it.success) project.service<WorktreeCacheService>().refreshBlocking()
                }
            },
        ) { result -> notifyResult(result, "Worktree moved") }
    }

    private fun reapplyCarryOverSelected() {
        val worktree = selectedWorktree() ?: return
        project.service<WorktreeCarryOverService>().reapplyCarryOverAsync(worktree) { result ->
            CarryOverResultDialog.showResult(project, result)
            refresh()
        }
    }

    private fun notifyResult(
        result: WorktreeCommandResult,
        successTitle: String,
    ) {
        if (result.success) {
            Notifications.info(project, successTitle, result.output.ifBlank { "Done" })
            refresh()
        } else {
            Notifications.error(project, "$successTitle failed", result.output.ifBlank { "Git command failed" })
        }
    }

    private fun <T> runBackground(
        title: String,
        work: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    val result = runCatching(work)
                    ApplicationManager.getApplication().invokeLater {
                        result.onSuccess(onSuccess)
                        result.onFailure {
                            Notifications.error(project, title, it.message ?: it.javaClass.simpleName)
                        }
                    }
                }
            },
        )
    }

    private class WorktreeListCellRenderer :
        JPanel(BorderLayout()),
        ListCellRenderer<WorktreeRow> {
        private val title = JLabel()
        private val subtitle = JLabel()
        private val meta = JLabel()
        private val badges = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        private val textPanel =
            JPanel(GridLayout(0, 1, 0, 2)).apply {
                isOpaque = false
                add(title)
                add(subtitle)
                add(meta)
            }

        init {
            isOpaque = true
            border = JBUI.Borders.empty(7, 12, 7, 12)
            badges.isOpaque = false
            title.font = title.font.deriveFont(Font.BOLD)
            subtitle.font = JBUI.Fonts.smallFont()
            meta.font = JBUI.Fonts.smallFont()
            add(textPanel, BorderLayout.CENTER)
            add(badges, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out WorktreeRow>,
            value: WorktreeRow?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            background = if (isSelected) list.selectionBackground else list.background
            val primary =
                if (isSelected) {
                    list.selectionForeground
                } else if (value?.safeToDelete == true) {
                    JBColor.GRAY
                } else {
                    list.foreground
                }
            val secondary = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
            title.foreground = primary
            subtitle.foreground = secondary
            meta.foreground = secondary
            title.text = value?.let(::worktreeTitle).orEmpty()
            subtitle.text = value?.let(::worktreeSubtitle).orEmpty()
            meta.text = value?.let(::worktreeMeta).orEmpty()
            meta.isVisible = meta.text.isNotBlank()
            badges.removeAll()
            value?.let { row ->
                worktreeBadges(row).forEach { badge ->
                    badges.add(createBadgeLabel(badge, isSelected, list))
                }
            }
            return this
        }

        private fun createBadgeLabel(
            badge: WorktreeBadge,
            isSelected: Boolean,
            list: JList<out WorktreeRow>,
        ): JLabel =
            JLabel(badge.text).apply {
                font = JBUI.Fonts.smallFont()
                border = JBUI.Borders.empty(1, 5, 1, 5)
                foreground = if (isSelected) list.selectionForeground else badge.tone.foreground
                background = if (isSelected) list.selectionBackground else badge.tone.background
                isOpaque = true
                toolTipText = badge.tooltip
            }
    }

    private data class CreateResult(
        val command: WorktreeCommandResult,
        val worktree: WorktreeInfo?,
    )
}

private object SearchableChooserPopup {
    fun <T : Any> show(
        anchor: JComponent,
        items: List<T>,
        selectedItem: T?,
        emptyText: String,
        labelProvider: (T) -> String,
        iconProvider: (T) -> Icon?,
        onSelected: (T) -> Unit,
    ) {
        val listModel = DefaultListModel<T>()
        val list =
            JBList(listModel).apply {
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                cellRenderer =
                    object : SimpleListCellRenderer<T>() {
                        override fun customize(
                            list: JList<out T>,
                            value: T,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean,
                        ) {
                            text = if (value == selectedItem) "✓ ${labelProvider(value)}" else labelProvider(value)
                            icon = iconProvider(value)
                        }
                    }
            }
        val search = SearchTextField()
        val content =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8)
                add(search, BorderLayout.NORTH)
                add(
                    JBScrollPane(list).apply {
                        border = JBUI.Borders.emptyTop(6)
                        preferredSize = Dimension(CHOOSER_POPUP_WIDTH, CHOOSER_POPUP_HEIGHT)
                    },
                    BorderLayout.CENTER,
                )
            }

        fun refill() {
            val query = search.text.trim().lowercase()
            listModel.clear()
            items
                .asSequence()
                .filter { query.isBlank() || query in labelProvider(it).lowercase() }
                .forEach(listModel::addElement)
            list.emptyText.text = emptyText
            if (!listModel.isEmpty) list.selectedIndex = 0
        }
        search.textEditor.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    refill()
                }
            },
        )
        refill()
        val popup =
            JBPopupFactory
                .getInstance()
                .createComponentPopupBuilder(content, search.textEditor)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .createPopup()

        fun chooseSelected() {
            list.selectedValue?.let {
                onSelected(it)
                popup.closeOk(null)
            }
        }
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(event)) return
                    val index = list.locationToIndex(event.point)
                    if (index >= 0 && list.getCellBounds(index, index)?.contains(event.point) == true) {
                        list.selectedIndex = index
                        chooseSelected()
                    }
                }
            },
        )
        list.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    if (event.keyCode == KeyEvent.VK_ENTER) {
                        event.consume()
                        chooseSelected()
                    }
                }
            },
        )
        popup.show(RelativePoint(anchor, Point(0, anchor.height + JBUIScale.scale(4))))
    }
}

private class SelectFilterComponent<T : Any>(
    private val filterName: String,
    private val itemsProvider: () -> List<T>,
    private val onSelection: (T?) -> Unit,
    private val valuePresenter: (T) -> String,
    private val iconProvider: (T) -> Icon? = { null },
) : FilterComponent(Supplier { filterName }) {
    private var selection: T? = null

    init {
        setShowPopupAction { showPopup() }
        initUi()
        UIUtil.setTooltipRecursively(this, filterName)
    }

    override fun getCurrentText(): String = selection?.let(valuePresenter) ?: emptyFilterValue

    override fun getEmptyFilterValue(): String = ""

    override fun isValueSelected(): Boolean = selection != null

    override fun installChangeListener(onChange: Runnable) = Unit

    override fun createResetAction(): Runnable = Runnable { select(null) }

    override fun shouldDrawLabel(): DrawLabelMode = DrawLabelMode.WHEN_VALUE_NOT_SET

    fun clearSelection() {
        selection = null
        refreshPresentation()
    }

    private fun showPopup() {
        SearchableChooserPopup.show(
            anchor = this,
            items = itemsProvider(),
            selectedItem = selection,
            emptyText = "No $filterName values",
            labelProvider = valuePresenter,
            iconProvider = iconProvider,
            onSelected = ::select,
        )
    }

    private fun select(value: T?) {
        selection = value
        refreshPresentation()
        onSelection(value)
    }

    private fun refreshPresentation() {
        removeAll()
        initUi()
        revalidate()
        repaint()
    }
}

private class CreatorAvatarIcon(
    private val name: String,
) : Icon {
    override fun getIconWidth(): Int = AVATAR_SIZE

    override fun getIconHeight(): Int = AVATAR_SIZE

    override fun paintIcon(
        component: Component?,
        graphics: Graphics,
        x: Int,
        y: Int,
    ) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = avatarColor(name)
            g.fillOval(x, y, AVATAR_SIZE, AVATAR_SIZE)
            g.color = Color.WHITE
            g.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD, AVATAR_FONT_SIZE.toFloat())
            val initials = creatorInitials(name)
            val metrics = g.fontMetrics
            val textX = x + (AVATAR_SIZE - metrics.stringWidth(initials)) / 2
            val textY = y + ((AVATAR_SIZE - metrics.height) / 2) + metrics.ascent
            g.drawString(initials, textX, textY)
        } finally {
            g.dispose()
        }
    }
}

private fun avatarColor(name: String): Color = Color.getHSBColor((name.hashCode() and 0xFF) / 255f, AVATAR_SATURATION, AVATAR_BRIGHTNESS)

private fun creatorInitials(name: String): String =
    name
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "?" }

private enum class WorktreeQuickFilter(
    val label: String,
) {
    All("All"),
    Current("Current"),
    SafeToDelete("Safe to delete"),
    Dirty("Dirty"),
    Locked("Locked"),
    Prunable("Prunable"),
    Detached("Detached"),
    Main("Main"),
    ;

    fun matches(row: WorktreeRow): Boolean =
        when (this) {
            All -> true
            Current -> row.info.isCurrent
            SafeToDelete -> row.safeToDelete
            Dirty -> row.statusDetails?.dirty == true
            Locked -> row.info.isLocked
            Prunable -> row.info.isPrunable
            Detached -> row.info.isDetached
            Main -> row.info.isMain
        }

    override fun toString(): String = label
}

private enum class WorktreeStateFilter(
    val label: String,
) {
    Current("Current"),
    Main("Main"),
    Clean("Clean"),
    Dirty("Dirty"),
    SafeToDelete("Safe to delete"),
    Locked("Locked"),
    Prunable("Prunable"),
    Detached("Detached"),
    ;

    fun matches(row: WorktreeRow): Boolean =
        when (this) {
            Current -> row.info.isCurrent
            Main -> row.info.isMain
            Clean -> row.statusDetails?.dirty == false
            Dirty -> row.statusDetails?.dirty == true
            SafeToDelete -> row.safeToDelete
            Locked -> row.info.isLocked
            Prunable -> row.info.isPrunable
            Detached -> row.info.isDetached
        }

    override fun toString(): String = label
}

private enum class WorktreeSortMode(
    val label: String,
) {
    MostRecentlyUpdated("Most recently updated"),
    LeastRecentlyUpdated("Least recently updated"),
    MostRecentlyCreated("Most recently created"),
    LeastRecentlyCreated("Least recently created"),
    BranchAscending("Branch name (ascending)"),
    BranchDescending("Branch name (descending)"),
    State("State"),
    Creator("Creator"),
    PathAscending("Path (ascending)"),
    PathDescending("Path (descending)"),
    ;

    fun sort(rows: Sequence<WorktreeRow>): List<WorktreeRow> =
        when (this) {
            MostRecentlyUpdated -> rows.sortedWith(compareByDescending<WorktreeRow> { it.statusDetails?.commitEpochSeconds ?: Long.MIN_VALUE })
            LeastRecentlyUpdated -> rows.sortedWith(compareBy<WorktreeRow> { it.statusDetails?.commitEpochSeconds ?: Long.MAX_VALUE })
            MostRecentlyCreated -> rows.sortedWith(compareByDescending<WorktreeRow> { it.statusDetails?.creatorEpochSeconds ?: Long.MIN_VALUE })
            LeastRecentlyCreated -> rows.sortedWith(compareBy<WorktreeRow> { it.statusDetails?.creatorEpochSeconds ?: Long.MAX_VALUE })
            BranchAscending -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.branch })
            BranchDescending -> rows.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.branch })
            State -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { primaryStateLabel(it) })
            Creator -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.creator })
            PathAscending -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
            PathDescending -> rows.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.path })
        }.toList()

    override fun toString(): String = label
}

private fun primaryStateLabel(row: WorktreeRow): String =
    when {
        row.info.isCurrent -> WorktreeStateFilter.Current.label
        row.info.isMain -> WorktreeStateFilter.Main.label
        row.statusDetails?.dirty == true -> WorktreeStateFilter.Dirty.label
        row.safeToDelete -> WorktreeStateFilter.SafeToDelete.label
        row.info.isLocked -> WorktreeStateFilter.Locked.label
        row.info.isPrunable -> WorktreeStateFilter.Prunable.label
        row.info.isDetached -> WorktreeStateFilter.Detached.label
        row.statusDetails?.dirty == false -> WorktreeStateFilter.Clean.label
        else -> "Unknown"
    }

internal fun rowSearchValues(row: WorktreeRow): List<String> =
    buildList {
        add(row.branch)
        add(row.status)
        add(row.headDelta)
        add(row.mainDivergence)
        add(row.remoteDivergence)
        add(row.path)
        add(row.info.path.toString())
        add(row.info.repositoryRoot.toString())
        add(row.commit)
        add(row.age)
        add(row.message)
        if (row.creator.isNotBlank()) add("creator")
        add(row.creator)
        add(row.statusDetails?.creatorEmail.orEmpty())
        add(primaryStateLabel(row))
        add(row.info.lockReason.orEmpty())
        add(row.info.prunableReason.orEmpty())
        if (row.info.isMain) add("main")
        if (row.info.isCurrent) add("current")
        if (row.info.isLocked) add("locked")
        if (row.info.isPrunable) add("prunable")
        if (row.info.isDetached) add("detached")
        if (row.safeToDelete) add("safe delete cleanup removable")
        if (row.statusDetails?.dirty == true) add("dirty")
    }

internal fun rowMatchesFilter(
    values: List<String>,
    tokens: List<String>,
): Boolean {
    val haystack = values.joinToString(" ").lowercase()
    return tokens.all { it.lowercase() in haystack }
}

internal fun worktreeTitle(row: WorktreeRow): String = row.branch

internal fun worktreeSubtitle(row: WorktreeRow): String =
    listOf(row.path, row.commit, row.age)
        .filter { it.isNotBlank() && it != "·" }
        .joinToString(" · ")

internal fun worktreeMeta(row: WorktreeRow): String =
    buildList {
        if (row.message.isNotBlank()) add(row.message)
        if (row.creator.isNotBlank()) add("Creator: ${row.creator}")
        if (row.info.lockReason != null) add("Locked: ${row.info.lockReason}")
        if (row.info.prunableReason != null) add("Prunable: ${row.info.prunableReason}")
        if (row.safeToDelete) add("Safe to delete")
    }.joinToString(" · ")

internal fun worktreeTooltip(
    row: WorktreeRow,
    cellX: Int,
    cellY: Int,
    cellWidth: Int,
    cellHeight: Int,
): String? {
    if (cellWidth - cellX <= BADGE_TOOLTIP_REGION_WIDTH) return worktreeBadgesTooltip(row)
    val line = ((cellY * LIST_ITEM_LINE_COUNT) / cellHeight.coerceAtLeast(1)).coerceIn(0, LIST_ITEM_LINE_COUNT - 1)
    return when (line) {
        0 -> worktreeBranchTooltip(row)
        1 -> worktreeSubtitleTooltip(row, cellX, cellWidth)
        else -> worktreeDetailsTooltip(row)
    }
}

private fun worktreeSubtitleTooltip(
    row: WorktreeRow,
    cellX: Int,
    cellWidth: Int,
): String =
    when {
        cellX < cellWidth / 2 -> worktreePathTooltip(row)
        cellX < cellWidth * 2 / 3 -> worktreeCommitTooltip(row)
        else -> worktreeAgeTooltip(row)
    }

private fun worktreeBranchTooltip(row: WorktreeRow): String =
    htmlTooltip(
        "Branch / ref",
        row.branch,
        when {
            row.info.isMain -> "Main worktree"
            row.info.isDetached -> "Detached HEAD"
            row.info.isCurrent -> "Current project worktree"
            else -> null
        },
    )

private fun worktreePathTooltip(row: WorktreeRow): String =
    htmlTooltip(
        "Location",
        "Relative: ${row.path}",
        "Absolute: ${row.info.path}",
        "Repository: ${row.info.repositoryRoot}",
    )

private fun worktreeCommitTooltip(row: WorktreeRow): String =
    htmlTooltip(
        "Commit",
        "Commit: ${row.commit}",
        row.creator.takeIf(String::isNotBlank)?.let { "Creator: $it" },
        row.statusDetails?.creatorEmail?.let { "Creator email: $it" },
        row.message.ifBlank { null },
    )

private fun worktreeAgeTooltip(row: WorktreeRow): String =
    htmlTooltip(
        "Commit age",
        if (row.age == "·") "Commit time unavailable" else "Last commit: ${row.age} ago",
    )

private fun worktreeDetailsTooltip(row: WorktreeRow): String =
    htmlTooltip(
        "Details",
        row.message.ifBlank { null },
        row.creator.takeIf(String::isNotBlank)?.let { "Creator: $it" },
        row.info.lockReason?.let { "Locked: $it" },
        row.info.prunableReason?.let { "Prunable: $it" },
        if (row.safeToDelete) "Safe to delete" else null,
    )

private fun worktreeBadgesTooltip(row: WorktreeRow): String =
    htmlTooltipLines(
        "State badges",
        worktreeBadges(row).map { "${it.text}: ${it.tooltip}" }.ifEmpty { listOf("No state badges") },
    )

private fun htmlTooltip(
    title: String,
    vararg lines: String?,
): String = htmlTooltipLines(title, lines.asIterable())

private fun htmlTooltipLines(
    title: String,
    lines: Iterable<String?>,
): String =
    lines
        .filterNotNull()
        .filter { it.isNotBlank() }
        .joinToString(separator = "<br>", prefix = "<html><b>${escapeHtml(title)}</b><br>", postfix = "</html>") { escapeHtml(it) }

private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private val QUICK_FILTER_ICON = BadgeIconSupplier(AllIcons.General.Filter)

private const val LIST_ITEM_LINE_COUNT = 3
private const val BADGE_TOOLTIP_REGION_WIDTH = 260
private const val SEARCH_HISTORY_LIMIT = 20
private const val NO_SEARCH_HISTORY_ITEM = "No recent searches"
private const val CHOOSER_POPUP_WIDTH = 320
private const val CHOOSER_POPUP_HEIGHT = 260
private const val AVATAR_SIZE = 16
private const val AVATAR_FONT_SIZE = 9
private const val AVATAR_SATURATION = 0.55f
private const val AVATAR_BRIGHTNESS = 0.72f
private const val INITIAL_EMPTY_REFRESH_RETRIES = 2
private const val INITIAL_EMPTY_REFRESH_DELAY_MS = 750
private const val EXTERNAL_REFRESH_DELAY_MS = 400

private fun worktreeBadges(row: WorktreeRow): List<WorktreeBadge> = worktreeStateBadges(row) + worktreeChangeBadges(row) + worktreeDivergenceBadges(row)

private fun worktreeStateBadges(row: WorktreeRow): List<WorktreeBadge> =
    buildList {
        if (row.info.isMain) add(WorktreeBadge("MAIN", "Main worktree", BadgeTone.Info))
        if (row.info.isCurrent) add(WorktreeBadge("CURRENT", "Current project worktree", BadgeTone.Info))
        if (row.safeToDelete) add(WorktreeBadge("SAFE", "Clean and safe to delete", BadgeTone.Success))
        if (row.info.isLocked) add(WorktreeBadge("LOCKED", row.info.lockReason ?: "Locked worktree", BadgeTone.Warning))
        if (row.info.isPrunable) add(WorktreeBadge("PRUNABLE", row.info.prunableReason ?: "Prunable worktree", BadgeTone.Warning))
        if (row.info.isDetached) add(WorktreeBadge("DETACHED", "Detached HEAD", BadgeTone.Neutral))
    }

private fun worktreeChangeBadges(row: WorktreeRow): List<WorktreeBadge> =
    buildList {
        val status = row.statusDetails ?: return@buildList
        if (status.dirty) add(WorktreeBadge("DIRTY", "Working tree has changes", BadgeTone.Danger))
        if (status.stagedCount > 0) add(WorktreeBadge("S${status.stagedCount}", "Staged files", BadgeTone.Warning))
        if (status.unstagedCount > 0) add(WorktreeBadge("U${status.unstagedCount}", "Unstaged files", BadgeTone.Warning))
        if (status.untrackedCount > 0) add(WorktreeBadge("?${status.untrackedCount}", "Untracked files", BadgeTone.Warning))
        if (row.headDelta != "·") add(WorktreeBadge(row.headDelta, "Changed lines at HEAD", BadgeTone.Neutral))
    }

private fun worktreeDivergenceBadges(row: WorktreeRow): List<WorktreeBadge> =
    buildList {
        if (row.mainDivergence != "·" && row.mainDivergence != "|") {
            add(WorktreeBadge("main ${row.mainDivergence}", "Divergence from main", BadgeTone.Neutral))
        }
        if (row.remoteDivergence != "·" && row.remoteDivergence != "|") {
            add(WorktreeBadge("remote ${row.remoteDivergence}", "Divergence from remote", BadgeTone.Neutral))
        }
    }

private data class WorktreeBadge(
    val text: String,
    val tooltip: String,
    val tone: BadgeTone,
)

private enum class BadgeTone(
    val foreground: JBColor,
    val background: JBColor,
) {
    Neutral(
        JBColor.namedColor("Label.foreground", JBColor.foreground()),
        JBColor.namedColor("Label.background", JBColor(0xE9EDF2, 0x3C3F41)),
    ),
    Info(
        JBColor.namedColor("Label.infoForeground", JBColor(0x2454A6, 0xA8C7FA)),
        JBColor.namedColor("Label.infoBackground", JBColor(0xE6F0FF, 0x26324A)),
    ),
    Success(
        JBColor.namedColor("Label.successForeground", JBColor(0x1E6B33, 0x7BD88F)),
        JBColor.namedColor("Label.successBackground", JBColor(0xE6F5EA, 0x243A2A)),
    ),
    Warning(
        JBColor.namedColor("Label.warningForeground", JBColor(0x8A5A00, 0xF0C36D)),
        JBColor.namedColor("Label.warningBackground", JBColor(0xFFF4D6, 0x3F3218)),
    ),
    Danger(
        JBColor.namedColor("Label.errorForeground", JBColor(0xA12722, 0xFF8A80)),
        JBColor.namedColor("Label.errorBackground", JBColor(0xFCE8E6, 0x4A2525)),
    ),
}
