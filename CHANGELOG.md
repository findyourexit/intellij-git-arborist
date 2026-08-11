# Changelog

All notable changes to Git Arborist are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-08-11

### Added

- The Worktrees tool window now keeps itself current when worktrees are added, removed, or moved outside Git Arborist (the Git CLI, other plugins, or other IDE actions). It refreshes silently on Git4Idea repository changes while visible, and when the tool window is reopened, so the list no longer goes stale until a manual refresh.
- Worktree rows now show contributor avatars on the first line, modeled on the Pull Requests list's assignees/reviewers: the branch creator's avatar, a separator, then up to five committer avatars (the fifth collapses to a "+N" counter) for everyone who has committed on the branch. Avatars start as initial monograms and are replaced in place once fetched — GitHub `users.noreply.github.com` commit emails resolve to the contributor's GitHub avatar (the same image the built-in Pull Requests window shows), and all other addresses fall back to Gravatar, with the monogram kept when neither exists. An opt-in **Fetch real GitHub avatars** setting (off by default, under Version Control > Git Arborist) additionally resolves any other commit email to its GitHub avatar through the GitHub commits API for worktrees whose remote is `github.com`, authenticating with the GitHub token Git already stores (`git credential fill`) for the higher rate limit and private repositories. Lookups are cached for the session; hovering names them.
- New worktrees can now be created directly from a **remote branch**. A **New Worktree from Remote Branch** title action (and matching `Git > Worktrees` menu entry) opens a searchable list of the repository's remote-tracking branches, with a **Fetch** button that pulls every remote — authentication and progress handled by the IDE — and refreshes the list in place. Picking a branch seeds a new local branch that tracks it, defaults the target path from the branch name, and creates the worktree through the same flow as a manual create. This closes the gap where checking out a remote branch onto a fresh worktree previously required typing the full `origin/…` ref by hand.
- Worktrees can also be created from the **Git Log**: right-click a commit that carries a branch label and choose **Create Worktree from Branch…**. A remote branch seeds a new local tracking branch; a local branch is checked out into the new worktree. This uses only the platform's public VCS Log data, so it stays compatible across IDE builds. (The Git Branches popup widget is intentionally not hooked: its branch-selection APIs are marked internal and off-limits to third-party plugins, and it already ships its own built-in "New Worktree" action.)

### Fixed

- Settings changed just before opening a worktree now carry over reliably. Carry-over reads the source project's `.idea/` from disk, but the IDE debounces `PersistentStateComponent` writes and otherwise flushes them only on frame deactivation or project close — so settings edited moments earlier, most visibly **Settings | Tools** entries from third-party plugins (Detekt, Develocity, KtLint, …) persisted to `.idea/` XML files, could be copied stale or missing. The open source project's settings are now flushed to disk before the copy runs.
- The Worktrees tool window no longer pushes status badges and text off the right edge when the panel is narrow. Each row now tracks the tool window width (no horizontal scrollbar), the branch/path/commit text keeps a minimum width and truncates with an ellipsis, and the status badges shrink-then-clip while staying pinned to the right edge — so neither side disappears as the panel shrinks, matching the built-in Pull Requests tool window in constrained widths.

### Changed

- Reworked each worktree row for density and legibility, aligning with the Pull Requests tool window: the branch name is no longer bold, MAIN/CURRENT/DIRTY/SAFE sit as pills beside the title (where DRAFT appears), the commit message moves up to the second line, and the remaining detail (locked, prunable, detached, staged/unstaged/untracked counts, line delta, and main/remote divergence) drops to a third line. The relative path is hidden but still shown on hover.
- Every third-party GitHub Actions step in the CI and release workflows is now pinned to a full commit SHA instead of a mutable tag (`actions/checkout`, `actions/setup-java`, `actions/upload-artifact`, `gradle/actions/wrapper-validation`, `gradle/actions/setup-gradle`). Tags such as `@v7` are movable refs, so an upstream account compromise could have silently swapped the code that builds, verifies, and signs the released plugin. Each pin carries a trailing `# vX.Y.Z` comment, so Dependabot keeps updating them as before while the exact reviewed commit stays fixed.

## [0.1.1] - 2026-06-23

### Removed

- The **Open in New Window**, **Open as Tab**, and **Replace Current Project with Worktree** open modes, their Project View actions, and the "ask each time" prompt. Opening a worktree now always hands off to the IDE's standard project-open flow (open in this window, a new window, or cancel), which already honors your IDE's window-vs-tab preference. The "default open mode" setting is replaced by an "open new worktrees after creation" toggle.

### Fixed

- Opening a worktree no longer risks a `NoSuchMethodError` on IntelliJ 2025.3, 2026.1, and 2026.2 EAP. The former "open as tab" mode relied on `OpenProjectTask.copy(...)`, whose compiler-generated `copy$default` signature changes between platform builds; delegating to the IDE's open flow removes that dependency — and the internal-API workaround it would otherwise require — entirely.
- Replaced the searchable-chooser popup's use of the scheduled-for-removal `SimpleListCellRenderer.create(...)` (flagged on 2026.2) with a non-deprecated `SimpleListCellRenderer` subclass; the popup looks and behaves the same.

### Changed

- The Plugin Verifier release gate now verifies the latest released **and EAP** IntelliJ IDEA builds (auto-resolved, alongside the build-252 floor) and fails on internal, scheduled-for-removal, non-extendable, and override-only API usage, so forward-compatibility problems surface before upload.

## [0.1.0] - 2026-06-22

### Added

- Worktrees tool window presenting each worktree as a list row with branch title, path/commit/age subtitle, commit-message and creator details, and state badges for main, current, safe-to-delete, dirty, locked, prunable, detached, staged/unstaged/untracked counts, HEAD line delta, and divergence from `main` and the remote.
- Search, quick filters, and State, Creator, and Sort choosers over the worktree list, with contextual tooltips and a right-click context menu.
- Git4Idea-backed worktree operations: list, create, open, remove (with optional force and backing-branch deletion), lock, unlock, move, prune, repair, and status loading, all run off the UI thread.
- Open modes for new window, tab on the current frame, replace current project, and the IDE-default project-open prompt, focusing an already-open worktree instead of opening a duplicate.
- Carry-over on first open that copies `.idea/` and `.worktree-copy` manifest entries (optionally all git-ignored files) from the configured source into a new worktree before it opens, enforcing sensitive and heavy-directory denylists, never overwriting existing files, and preserving symlinks without following them outside the source root.
- Carry-over result dialog with copied, skipped, rejected, and failed counts, open gating on copy failures, and explicit reapply actions.
- Safe-to-delete detection that dims and badges worktrees whose work is fully merged into the default branch or its upstream.
- `Git > Worktrees` main-menu group, Project View context group on worktree directories, and a tool-window title drawn from the repository's `owner/repo` identity.
- Settings under Version Control > Git Arborist for the default worktree directory, open mode, carry-over scope and source, `.idea/` copying, manifest file name, automatic-carry-over guard, heavy-path opt-in, and relative locations, with an optional per-project override.

[Unreleased]: https://github.com/findyourexit/intellij-git-arborist/compare/0.2.0...HEAD
[0.2.0]: https://github.com/findyourexit/intellij-git-arborist/compare/0.1.1...0.2.0
[0.1.1]: https://github.com/findyourexit/intellij-git-arborist/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/findyourexit/intellij-git-arborist/commits/0.1.0
