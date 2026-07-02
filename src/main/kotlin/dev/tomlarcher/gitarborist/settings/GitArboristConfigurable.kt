package dev.tomlarcher.gitarborist.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.tomlarcher.gitarborist.carry.CarryOverScope
import dev.tomlarcher.gitarborist.carry.CarryOverSource

/** Settings UI under Version Control > Git Arborist for the global defaults and project override. */
class GitArboristConfigurable(
    private val project: Project,
) : BoundConfigurable("Git Arborist") {
    override fun createPanel(): DialogPanel {
        val global = service<GitArboristSettings>().state
        val projectState = project.service<GitArboristProjectSettings>().state

        return panel {
            group("Global defaults") {
                row("Default worktree directory:") {
                    textField().bindText(global::defaultWorktreeDirectory)
                }
                row {
                    checkBox("Open new worktrees after creation").bindSelected(global::openAfterCreate)
                }
                row("Carry-over scope:") {
                    comboBox(CarryOverScope.entries.toList())
                        .bindItem({ global.carryOverScope }, { global.carryOverScope = it ?: CarryOverScope.Curated })
                }
                row("Carry-over source:") {
                    comboBox(CarryOverSource.entries.toList())
                        .bindItem({ global.carryOverSource }, { global.carryOverSource = it ?: CarryOverSource.MainWorktree })
                }
                row {
                    checkBox("Copy .idea/ during curated carry-over").bindSelected(global::copyIdeaDirectory)
                }
                row("Manifest file name:") {
                    textField().bindText(global::manifestFileName)
                }
                row {
                    checkBox("Run automatic carry-over only when target .idea/ is missing").bindSelected(global::runCarryOverOnlyWhenIdeaMissing)
                }
                row {
                    checkBox("Allow heavy manifest paths").bindSelected(global::allowHeavyManifestPaths)
                }
                row {
                    checkBox("Show relative worktree locations").bindSelected(global::showRelativeLocations)
                }
                row {
                    checkBox("Fetch real GitHub avatars for contributors (uses your saved GitHub Git credentials)")
                        .bindSelected(global::resolveGitHubAvatars)
                        .comment(
                            "When on, contributor monograms are replaced by GitHub avatars resolved through the GitHub API " +
                                "for worktrees whose remote is github.com. Falls back to Gravatar and monograms otherwise.",
                        )
                }
            }

            group("Project override") {
                row {
                    checkBox("Use project settings").bindSelected(projectState::useProjectSettings)
                }
                row("Default worktree directory:") {
                    textField().bindText(projectState::defaultWorktreeDirectory)
                }
                row {
                    checkBox("Open new worktrees after creation").bindSelected(projectState::openAfterCreate)
                }
                row("Carry-over scope:") {
                    comboBox(CarryOverScope.entries.toList())
                        .bindItem({ projectState.carryOverScope }, { projectState.carryOverScope = it ?: CarryOverScope.Curated })
                }
                row("Carry-over source:") {
                    comboBox(CarryOverSource.entries.toList())
                        .bindItem({ projectState.carryOverSource }, { projectState.carryOverSource = it ?: CarryOverSource.MainWorktree })
                }
                row {
                    checkBox("Copy .idea/ during curated carry-over").bindSelected(projectState::copyIdeaDirectory)
                }
                row("Manifest file name:") {
                    textField().bindText(projectState::manifestFileName)
                }
                row {
                    checkBox("Run automatic carry-over only when target .idea/ is missing").bindSelected(projectState::runCarryOverOnlyWhenIdeaMissing)
                }
                row {
                    checkBox("Allow heavy manifest paths").bindSelected(projectState::allowHeavyManifestPaths)
                }
                row {
                    checkBox("Show relative worktree locations").bindSelected(projectState::showRelativeLocations)
                }
            }
        }
    }
}
