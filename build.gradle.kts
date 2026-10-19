import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation("junit:junit:4.13.2")
    // Virtual-time testing of the idle producer; the platform already bundles kotlinx-coroutines-core, so only the
    // test artifact is added and its transitive core dependency is excluded to avoid a duplicate on the classpath.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("version").map { pluginVersion ->
            val sponsorship = markdownToHTML(
                "_IDE Atmosphere is free and sponsor-supported. If you enjoy it, consider " +
                    "[♥ sponsoring it on GitHub](https://github.com/sponsors/hsz)._",
            )
            sponsorship + with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
}

tasks {
    runIde {
        // Periodic FPS / tick-cost / repaint statistics in the sandbox idea.log (see AtmosphereStats).
        jvmArgs("-Dide.atmosphere.debug=true")
    }

    test {
        // Marketplace / README media (MarketplaceMediaGenerator) render only when a target directory is given:
        // ./gradlew test --tests '*MarketplaceMediaGenerator' -Pide.atmosphere.media.dir=docs/media
        providers.gradleProperty("ide.atmosphere.media.dir").orNull?.let {
            systemProperty("ide.atmosphere.media.dir", layout.projectDirectory.dir(it).asFile.absolutePath)
            outputs.upToDateWhen { false }
        }
    }
}
