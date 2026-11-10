import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.platform.vcs")
        bundledModule("intellij.platform.vcs.core")
        testFramework(TestFrameworkType.Platform)
        // Only so the README images show highlighted Java, see DocImages.
        testBundledPlugin("com.intellij.java")
    }
    testImplementation("junit:junit:4.13.2")
    // Missing from the Platform test framework, see the IntelliJ Platform Gradle Plugin FAQ.
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        changeNotes = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText
            .zip(providers.gradleProperty("pluginVersion")) { changelog, version ->
                val heading = "## [$version] - "
                require(heading in changelog) { "CHANGELOG.md has no section for $version" }
                changelog.substringAfter(heading).substringAfter('\n').substringBefore("\n## ").trim()
                    .split(Regex("\n\\s*\n")).joinToString("\n") { block ->
                        val escaped = block.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<strong>$1</strong>")
                        if (escaped.startsWith("### ")) "<h3>${escaped.removePrefix("### ")}</h3>"
                        else "<ul><li>${escaped.removePrefix("- ").replace("\n- ", "</li><li>").replace('\n', ' ')}</li></ul>"
                    }
            }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    // `./gradlew test --tests '*DocImages*' -PdocImages` renders the README images into build/doc-images,
    // see docs/images/README.md. Without the property, DocImages does nothing.
    if (providers.gradleProperty("docImages").isPresent) {
        systemProperty("codesteps.docs.dir", layout.buildDirectory.dir("doc-images").get().asFile.path)
        outputs.upToDateWhen { false }
    }
}
