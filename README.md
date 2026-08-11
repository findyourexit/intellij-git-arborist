<div align="center">
  <img src="docs/assets/git-arborist-logo.png" alt="Git Arborist logo" width="128" />
</div>

<h1 align="center">Git Arborist</h1>

<p align="center">
  <strong>Git worktrees, first-class in your JetBrains IDE.</strong>
</p>

<p align="center">
  <a href="https://github.com/findyourexit/intellij-git-arborist/actions/workflows/ci.yml"><img src="https://github.com/findyourexit/intellij-git-arborist/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://plugins.jetbrains.com/plugin/32444-git-arborist"><img src="https://img.shields.io/jetbrains/plugin/v/32444?label=Marketplace&logo=jetbrains&logoColor=white" alt="JetBrains Marketplace version" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/IntelliJ%20Platform-2025.2%2B-000000?logo=intellijidea&logoColor=white" alt="IntelliJ Platform 2025.2+" />
  <img src="https://img.shields.io/badge/Android%20Studio-2025.2.1%2B-3DDC84?logo=androidstudio&logoColor=white" alt="Android Studio 2025.2.1+" />
  <img src="https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.10" />
  <img src="https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white" alt="JDK 21" />
</p>

Git Arborist makes Git worktrees first-class inside JetBrains IDEs. Create, open, and manage worktrees from a dedicated tool window, the **Git** menu, and the Project View — and carry your project setup (run configurations, code style, local tooling) into a new worktree automatically, before the IDE opens it for the first time.

> _A **Git worktree** checks out a second branch of the same repository into its own directory, so you can review a pull request without stashing, run a long build on one branch while you keep coding on another, or keep a release branch open beside `main`. This plugin removes the friction of driving that workflow from inside the IDE._

## Features

- **Worktrees tool window.** A dedicated panel shows each worktree — its branch, location, latest commit, and age — with state badges: current, uncommitted changes, divergence from `main` or the remote, and safe-to-delete.
- **Search, filter, and sort.** Look across branch, path, commit, message, creator, and state to find the worktree you want, even when there are many.
- **Full worktree lifecycle.** Create, open, remove, lock, move, prune, and repair worktrees without dropping to the command line.
- **Create from a remote branch.** Browse and filter the repository's remote branches, **Fetch** to refresh them, and spin up a worktree with a new local branch that tracks the one you picked — no typing the `origin/…` ref by hand.
- **One-click open.** Open any worktree as a project in this window or a new one; an already-open worktree is focused rather than opened again.
- **Carry over project setup.** New worktrees inherit your run configurations, code style, and local tooling the first time they open, while secrets and heavy build folders are left out.
- **Safe-to-delete detection.** Worktrees whose work is fully merged are dimmed and badged `SAFE`, so they're easy to spot and remove.
- **IDE integration.** Reach every action from the **Git** menu, the Project View, and the tool window.

## Compatibility

Git Arborist targets the [IntelliJ Platform](https://plugins.jetbrains.com/docs/intellij/intellij-platform.html), **version 2025.2 (build 252) or later**, with no upper bound. Its only dependencies are the platform itself and the bundled **Git** integration (`Git4Idea`), so it runs across the full range of JetBrains IDEs below — as long as Git is configured under **Settings → Version Control → Git**.

| IDE                                                                                                                         | Minimum version  |
|-----------------------------------------------------------------------------------------------------------------------------|------------------|
| IntelliJ IDEA (Ultimate & Community)                                                                                        | 2025.2           |
| Android Studio                                                                                                              | Otter (2025.2.1) |
| PyCharm (Professional & Community), WebStorm, PhpStorm, GoLand, CLion, RubyMine, RustRover, Rider, DataGrip, DataSpell, MPS | 2025.2           |

Every change is checked with the [JetBrains Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html) against IntelliJ IDEA Ultimate 2025.2.6.2 and Android Studio 2025.2.3.9, plus the latest stable and EAP IntelliJ IDEA builds from 2025.3 onward.

## Installation

### JetBrains Marketplace

In your IDE, open **Settings → Plugins → Marketplace**, search for **Git Arborist**, and click **Install**.

> [!TIP]
> It's also possible to install the plugin from the JetBrains Marketplace website, directly from the [Git Arborist plugin listing](https://plugins.jetbrains.com/plugin/32444-git-arborist).

### Manual Install (ZIP Distribution)

1. Download a release ZIP from the [Releases](https://github.com/findyourexit/intellij-git-arborist/releases) page, or build one (see [Building from source](#building-from-source)).
2. In your IDE, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**.
3. Select the ZIP and restart the IDE.

## Usage

Open the **Git Arborist** tool window from the right-hand tool-window bar, or run **Git → Worktrees → Open Worktrees Panel** from the top-menu.

- **Create** a worktree with the `+` title action. Choose a starting point (branch, tag, commit, or `HEAD`), optionally name a new branch, pick a target directory, and decide whether to open it afterward.
- **Create from a remote branch** with the **New Worktree from Remote Branch** title action, or from the **Git Log** by right-clicking a commit's branch label. Filter the remote-branch list, **Fetch** to refresh it, then pick a branch to seed a tracking local branch and its target directory.
- **Open** a worktree by double-clicking it, pressing Enter, the row's right-click **Open...** action, or the **Open Worktree** title action. The IDE's standard open prompt then decides whether it opens in this window or a new one.
- **Manage** worktrees from the row's right-click menu or the `Git > Worktrees` menu: lock, unlock, move, reapply carry-over, and remove. Removing offers force removal and, when a local branch backs the worktree, deletion of that branch.
- **Prune** and **Repair** the repository's worktree administrative data from the `Git > Worktrees` menu.

### Carry-Over on First Open

When a worktree is opened for the first time (detected by the absence of a `.idea/` directory in it), the plugin copies project setup from a source root — the main worktree by default — into the new worktree before the IDE opens it:

- `.idea/` is copied so run configurations and code style are available immediately.
- Every path listed in a `.worktree-copy` manifest at the source root is copied. The manifest is UTF-8 text, one path per line, relative to the source root; blank lines and lines starting with `#` are ignored.

Carry-over never overwrites a file that already exists in the target, preserves symlinks as symlinks (without following them outside the source root), and always skips a denylist of sensitive files (`.env`, `*.local`, anything matching `*secret*`, `*token*`, `*credential*`, `*private*`, and `.idea/httpRequests/`). Heavy build and dependency directories (`node_modules`, `build`, `.gradle`, `target`, and similar) are skipped unless you opt in. A result dialog reports what was copied, skipped, rejected, and failed; if any copy fails you are asked before the worktree opens. Use **Reapply Carry-over…** to run it again on an existing worktree.

### Settings

**Settings → Version Control → Git Arborist** configures the global defaults and an optional per-project override:

- Default worktree directory (used to seed the create dialog's target path).
- Whether to open new worktrees immediately after they are created.
- Carry-over scope (curated, all-ignored-minus-denylist, or manifest-only) and source (main worktree or current project).
- Whether to copy `.idea/`, the manifest file name, whether to run automatic carry-over only when the target `.idea/` is missing, whether to allow heavy manifest paths, and whether to show worktree locations relative to the repository root.

## Building From Source

**Prerequisites:** JDK 21 and Git on your `PATH`. The Gradle wrapper is included; no other tooling is required.

```bash
git clone https://github.com/findyourexit/intellij-git-arborist.git
cd intellij-git-arborist
./gradlew build
```

The plugin ZIP is written to `build/distributions/`.

```bash
./gradlew runIde          # launch a sandboxed IDE with the plugin installed
./gradlew test            # run the test suite
./gradlew ktlintCheck detekt   # run the style and static-analysis gates
./gradlew verifyPlugin    # run the JetBrains Plugin Verifier
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the build, test, and submission workflow, and [CHANGELOG.md](CHANGELOG.md) for release notes.

## Security

To report a vulnerability, follow the process in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
