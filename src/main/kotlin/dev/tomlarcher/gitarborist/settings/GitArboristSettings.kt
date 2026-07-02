package dev.tomlarcher.gitarborist.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.tomlarcher.gitarborist.carry.CarryOverScope
import dev.tomlarcher.gitarborist.carry.CarryOverSource

/** Application-level persistent global defaults for Git Arborist. */
@State(
    name = "GitArboristSettings",
    storages = [Storage("git-arborist.xml", roamingType = RoamingType.DISABLED)],
)
@Service(Service.Level.APP)
class GitArboristSettings : PersistentStateComponent<GitArboristSettings.State> {
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    data class State(
        var defaultWorktreeDirectory: String = ".worktrees",
        var openAfterCreate: Boolean = true,
        var carryOverScope: CarryOverScope = CarryOverScope.Curated,
        var carryOverSource: CarryOverSource = CarryOverSource.MainWorktree,
        var copyIdeaDirectory: Boolean = true,
        var manifestFileName: String = ".worktree-copy",
        var runCarryOverOnlyWhenIdeaMissing: Boolean = true,
        var allowHeavyManifestPaths: Boolean = false,
        var showRelativeLocations: Boolean = true,
        var resolveGitHubAvatars: Boolean = false,
    )
}
