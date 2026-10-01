import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)
    // Virtual-time testing of the idle producer; the platform already bundles kotlinx-coroutines-core, so only the
    // test artifact is added and its transitive core dependency is excluded to avoid a duplicate on the classpath.
    testImplementation(libs.kotlinx.coroutines.test) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // Optional at runtime (see <depends optional="true"> in plugin.xml): push reactions need the Git integration.
        bundledPlugin("Git4Idea")
    }
}

tasks {
    runIde {
        // Periodic FPS / tick-cost / repaint statistics in the sandbox idea.log (see AtmosphereStats).
        jvmArgs("-Dide.atmosphere.debug=true")
    }
}
