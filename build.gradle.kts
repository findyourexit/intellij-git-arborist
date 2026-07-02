import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

group = "dev.tomlarcher.gitarborist"
version = "0.2.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

ktlint {
    android.set(false)
    outputToConsole.set(true)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    parallel = true
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
    )
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)

    intellijPlatform {
        intellijIdea(libs.versions.intellijIdea.get())
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.tomlarcher.gitarborist"
        name = "Git Arborist"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252"
            // Open-ended compatibility: build 252 and all later builds, matching the README.
            // Each release is gated by the Plugin Verifier (IDEA Ultimate + Android Studio).
            untilBuild = provider { null }
        }

        vendor {
            name = "Tom Larcher"
            url = "https://github.com/findyourexit/intellij-git-arborist"
        }

        description =
            """
            <p>
            Git Arborist makes Git worktrees first-class inside JetBrains IDEs. Create, open, and
            manage worktrees from a dedicated tool window, the <b>Git</b> menu, and the Project View — and
            carry your project setup (run configurations, code style, local tooling) into a new worktree
            automatically, before the IDE opens it for the first time.
            </p>
            <p>
            A Git worktree checks out a second branch of the same repository into its own directory, so you
            can review a pull request without stashing, run a long build on one branch while you keep coding
            on another, or keep a release branch open beside <code>main</code>. This plugin removes the
            friction of driving that workflow from inside the IDE.
            </p>
            <p><b>Features</b></p>
            <ul>
            <li><b>Worktrees tool window</b> — each worktree is one row with a branch title, a path / commit /
            age subtitle, and state badges (main, current, safe-to-delete, dirty, locked, prunable, detached,
            staged/unstaged/untracked counts, and divergence from <code>main</code> and the remote). Status
            loads asynchronously and never blocks the UI.</li>
            <li><b>Search, filter, and sort</b> across branch, path, commit, message, creator, and state.</li>
            <li><b>New worktree from a remote branch</b> — browse and filter the repository's remote branches,
            fetch to refresh them, and create a worktree with a new local branch that tracks the one you pick;
            also available by right-clicking a branch in the Git Log.</li>
            <li><b>Every worktree operation through Git4Idea</b> — list, create, open, remove (with optional
            force and backing-branch deletion), lock, unlock, move, prune, and repair, all on a background
            thread.</li>
            <li><b>One-click open</b> — opening a worktree hands off to the IDE's own project-open flow
            (open in this window, a new window, or cancel — honoring your IDE preferences); an already-open
            worktree is focused instead of opened twice.</li>
            <li><b>Carry over project setup on first open</b> — copies <code>.idea/</code> and any paths listed
            in a <code>.worktree-copy</code> manifest into a new worktree before it opens. Secrets and heavy
            build directories are never copied.</li>
            <li><b>Safe-to-delete detection</b> — worktrees whose work is fully merged are dimmed and badged
            <code>SAFE</code>.</li>
            </ul>
            <p>
            Requires an IntelliJ Platform IDE (2025.2 or later, build 252+) that bundles the Git4Idea plugin,
            such as IntelliJ IDEA or Android Studio.
            </p>
            """.trimIndent()

        changeNotes =
            """
            Initial release: a Worktrees tool window, Git menu and Project View actions for create, open, remove, lock, move, prune, and repair, multiple open modes, and carry-over of project setup into a new worktree before its first open.
            """.trimIndent()
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, libs.versions.intellijIdea.get())
            create(IntelliJPlatformType.AndroidStudio, libs.versions.androidStudio.get())
            // Forward + EAP coverage, auto-resolved so it never goes stale: the latest released and
            // EAP IntelliJ IDEA builds at or above 2025.3. This surfaces binary-incompatible platform
            // drift (e.g. data-class copy$default) and not-yet-released deprecations before upload —
            // the gap that let the 2025.3/2026.1/2026.2 problems through the first time.
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaUltimate)
                channels = listOf(ProductRelease.Channel.RELEASE, ProductRelease.Channel.EAP)
                sinceBuild = "253"
                untilBuild = "999.*"
            }
        }
        failureLevel.set(
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
                VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
                VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
                VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
                VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
            ),
        )
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.check {
    dependsOn("ktlintCheck", "detekt")
}
