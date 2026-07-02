package dev.tomlarcher.gitarborist.ui

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.tomlarcher.gitarborist.git.AddWorktreeRequest
import dev.tomlarcher.gitarborist.git.RemoteBranch
import dev.tomlarcher.gitarborist.git.RemoteWorktreePlanner
import dev.tomlarcher.gitarborist.git.WorktreeInfo
import dev.tomlarcher.gitarborist.util.PathUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Modal list picker used by menu actions to choose a worktree to operate on. */
class WorktreePickerDialog(
    project: Project,
    private val worktrees: List<WorktreeInfo>,
    titleText: String,
) : DialogWrapper(project) {
    private val list = JBList(worktrees.map(::label))

    init {
        title = titleText
        init()
    }

    val selectedWorktree: WorktreeInfo?
        get() = worktrees.getOrNull(list.selectedIndex)

    override fun createCenterPanel(): JComponent =
        JScrollPane(list).apply {
            preferredSize = Dimension(720, 280)
        }

    companion object {
        private fun label(info: WorktreeInfo): String =
            buildString {
                append(info.branch ?: info.commitHash.take(12))
                append(" — ")
                append(info.path)
                if (info.isMain) append(" [main]")
                if (info.isLocked) append(" [locked]")
            }
    }
}

/**
 * Create-worktree dialog. Collects the starting point, optional new branch, target path, detach
 * toggle, and after-create open mode, defaulting the target from the branch name until the user edits
 * the target manually.
 */
class CreateWorktreeDialog(
    project: Project,
    repositoryRoot: Path,
    openByDefault: Boolean,
    private val worktreeDirectory: String,
    initialStartingPoint: String? = null,
    initialBranchName: String? = null,
    initialTargetName: String? = null,
) : DialogWrapper(project) {
    private val repositoryRootPath = PathUtil.normalize(repositoryRoot)
    private val sourceRefField = JBTextField("HEAD")
    private val branchField = JBTextField("")
    private val targetPathField = JBTextField(defaultTargetFor("worktree").toString())
    private val detachBox = JCheckBox("Create detached worktree", false)
    private val afterCreateBox = JComboBox(AfterCreateMode.entries.toTypedArray())
    private var updatingTarget = false
    private var targetEditedByUser = false

    init {
        title = "Create Worktree"
        branchField.emptyText.text = "e.g. findyourexit/feature-name"
        sourceRefField.emptyText.text = "HEAD, branch, tag, or commit SHA"
        afterCreateBox.selectedItem = AfterCreateMode.forOpenByDefault(openByDefault)
        branchField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!targetEditedByUser) {
                        updateTargetFromBranch()
                    }
                }
            },
        )
        targetPathField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!updatingTarget) targetEditedByUser = true
                }
            },
        )
        initialStartingPoint?.let { sourceRefField.text = it }
        initialBranchName?.let { branchField.text = it }
        initialTargetName?.let {
            updatingTarget = true
            targetPathField.text = defaultTargetFor(it).toString()
            updatingTarget = false
        }
        init()
    }

    val shouldOpenAfterCreate: Boolean
        get() = (afterCreateBox.selectedItem as AfterCreateMode).shouldOpen

    fun request(): AddWorktreeRequest =
        AddWorktreeRequest(
            repositoryRoot = repositoryRootPath,
            targetPath = Path.of(targetPathField.text.trim()),
            sourceRef = sourceRefField.text.trim().ifBlank { "HEAD" },
            branchName = branchField.text.trim().ifBlank { null },
            createBranch = branchField.text.isNotBlank() && !detachBox.isSelected,
            detach = detachBox.isSelected,
        )

    override fun createCenterPanel(): JComponent =
        JPanel(GridBagLayout()).apply {
            addFormRow("Starting point:", sourceRefField, row = 0)
            addFormRow("New branch:", branchField, row = 1)
            addFormRow("Target path:", targetPathField, row = 2)
            addFormRow("Checkout mode:", detachBox, row = 3)
            addFormRow("After create:", afterCreateBox, row = 4)
            preferredSize = Dimension(760, 190)
        }

    private fun updateTargetFromBranch() {
        updatingTarget = true
        targetPathField.text = defaultTargetFor(branchField.text.ifBlank { "worktree" }).toString()
        updatingTarget = false
    }

    private fun defaultTargetFor(branchName: String): Path = PathUtil.defaultWorktreeTarget(repositoryRootPath, worktreeDirectory, branchName)
}

/** What to do with a worktree immediately after it is created. */
enum class AfterCreateMode(
    private val label: String,
    val shouldOpen: Boolean,
) {
    DoNotOpen("Do not open", false),
    Open("Open...", true),
    ;

    override fun toString(): String = label

    companion object {
        fun forOpenByDefault(open: Boolean): AfterCreateMode = if (open) Open else DoNotOpen
    }
}

/**
 * New-worktree-from-a-remote-branch dialog. Lists the repository's remote-tracking branches for
 * selection (searchable), fetches every remote on demand, and collects the local branch name, target
 * path, and after-create open mode. The chosen remote branch seeds a new local tracking branch, and
 * the target path defaults from that branch name until the user edits it.
 */
class CheckoutRemoteBranchDialog(
    private val project: Project,
    repositoryRoot: Path,
    initialBranches: List<RemoteBranch>,
    openByDefault: Boolean,
    private val worktreeDirectory: String,
    private val fetcher: () -> List<RemoteBranch>,
) : DialogWrapper(project) {
    private val repositoryRootPath = PathUtil.normalize(repositoryRoot)
    private val listModel = CollectionListModel<RemoteBranch>()
    private val branchList =
        JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer =
                object : SimpleListCellRenderer<RemoteBranch>() {
                    override fun customize(
                        list: JList<out RemoteBranch>,
                        value: RemoteBranch?,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        value ?: return
                        text = value.shortHash?.let { "${value.trackingRef}  ·  $it" } ?: value.trackingRef
                    }
                }
        }
    private val searchField = SearchTextField()
    private val localBranchField = JBTextField("")
    private val targetPathField = JBTextField("")
    private val afterCreateBox = JComboBox(AfterCreateMode.entries.toTypedArray())
    private var allBranches = RemoteWorktreePlanner.sorted(initialBranches)
    private var updatingLocalBranch = false
    private var updatingTarget = false
    private var localBranchEditedByUser = false
    private var targetEditedByUser = false

    init {
        title = "New Worktree from Remote Branch"
        localBranchField.emptyText.text = "Local branch name"
        searchField.textEditor.emptyText.text = "Filter remote branches"
        afterCreateBox.selectedItem = AfterCreateMode.forOpenByDefault(openByDefault)
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = refillBranches()
            },
        )
        branchList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onBranchSelected()
        }
        localBranchField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!updatingLocalBranch) localBranchEditedByUser = true
                    if (!targetEditedByUser) updateTargetFromLocalBranch()
                }
            },
        )
        targetPathField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!updatingTarget) targetEditedByUser = true
                }
            },
        )
        init()
        refillBranches()
    }

    val shouldOpenAfterCreate: Boolean
        get() = (afterCreateBox.selectedItem as AfterCreateMode).shouldOpen

    fun request(): AddWorktreeRequest =
        RemoteWorktreePlanner.request(
            repositoryRoot = repositoryRootPath,
            targetPath = Path.of(targetPathField.text.trim()),
            branch = branchList.selectedValue,
            localBranchName = localBranchField.text,
        )

    override fun getPreferredFocusedComponent(): JComponent = searchField

    override fun doValidate(): ValidationInfo? =
        when {
            branchList.selectedValue == null -> ValidationInfo("Select a remote branch.", branchList)
            localBranchField.text.isBlank() -> ValidationInfo("Enter a local branch name.", localBranchField)
            targetPathField.text.isBlank() -> ValidationInfo("Enter a target path.", targetPathField)
            else -> null
        }

    override fun createCenterPanel(): JComponent =
        JPanel(BorderLayout(0, 8)).apply {
            add(topBar(), BorderLayout.NORTH)
            add(
                JBScrollPane(branchList).apply { preferredSize = Dimension(560, 240) },
                BorderLayout.CENTER,
            )
            add(form(), BorderLayout.SOUTH)
            preferredSize = Dimension(620, 460)
        }

    private fun topBar(): JComponent =
        JPanel(BorderLayout(8, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(
                JButton("Fetch").apply { addActionListener { fetch() } },
                BorderLayout.EAST,
            )
        }

    private fun form(): JComponent =
        JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            addFormRow("Local branch:", localBranchField, row = 0)
            addFormRow("Target path:", targetPathField, row = 1)
            addFormRow("After create:", afterCreateBox, row = 2)
        }

    private fun onBranchSelected() {
        val branch = branchList.selectedValue ?: return
        if (localBranchEditedByUser) return
        updatingLocalBranch = true
        localBranchField.text = RemoteWorktreePlanner.defaultLocalBranchName(branch)
        updatingLocalBranch = false
    }

    private fun updateTargetFromLocalBranch() {
        updatingTarget = true
        targetPathField.text =
            PathUtil.defaultWorktreeTarget(repositoryRootPath, worktreeDirectory, localBranchField.text.ifBlank { "worktree" }).toString()
        updatingTarget = false
    }

    private fun refillBranches() {
        val query = searchField.text.trim().lowercase()
        val filtered = allBranches.filter { query.isBlank() || query in it.trackingRef.lowercase() }
        listModel.replaceAll(filtered)
        branchList.emptyText.text =
            if (allBranches.isEmpty()) "No remote branches — Fetch to update." else "No matching remote branches"
        if (filtered.isNotEmpty()) branchList.selectedIndex = 0
    }

    private fun fetch() {
        var refreshed: List<RemoteBranch>? = null
        var failure: Exception? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    refreshed = fetcher()
                } catch (e: Exception) {
                    failure = e
                }
            },
            "Fetching Remote Branches",
            true,
            project,
        )
        when {
            failure != null -> Messages.showErrorDialog(project, failure?.message ?: "Fetch failed", "Fetch Failed")
            refreshed != null -> {
                allBranches = RemoteWorktreePlanner.sorted(refreshed.orEmpty())
                refillBranches()
            }
        }
    }
}

private fun JPanel.addFormRow(
    label: String,
    field: JComponent,
    row: Int,
) {
    add(
        JLabel(label),
        GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.LINE_END
            insets = Insets(4, 0, 4, 12)
        },
    )
    add(
        field,
        GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.LINE_START
            insets = Insets(4, 0, 4, 0)
        },
    )
}
