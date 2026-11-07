plugins {
    kotlin("jvm") version "2.5.0-Beta2" apply false
    kotlin("plugin.serialization") version "2.5.0-Beta2" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

allprojects {
    group = "io.github.ninebolt6.klass-tree"
    version = "0.1.0-SNAPSHOT"
}

val ktlintCli = configurations.create("ktlintCli")

dependencies {
    ktlintCli("io.github.ktlint.core:ktlint-cli:2.0.0-ALPHA-4:all@jar")
}

val ktlintPatterns =
    listOf(
        "*/src/**/*.kt",
        "*/src/**/*.kts",
        "*.gradle.kts",
        "*/build.gradle.kts",
        "!example/**",
    )

val ktlintCheck =
    tasks.register<JavaExec>("ktlintCheck") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs ktlint checks on all Kotlin sources."
        classpath = ktlintCli
        mainClass = "io.github.ktlint.core.Main"
        args(ktlintPatterns)
        inputs.files(
            fileTree(rootDir) {
                include("*/src/**/*.kt", "*/src/**/*.kts", "*.gradle.kts", "*/build.gradle.kts")
                exclude("example/**")
            },
        )
    }

tasks.register<JavaExec>("ktlintFormat") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Auto-formats Kotlin sources with ktlint."
    classpath = ktlintCli
    mainClass = "io.github.ktlint.core.Main"
    args(listOf("-F") + ktlintPatterns)
}

subprojects {
    apply(plugin = "dev.detekt")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
        }
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
    }

    plugins.withId("java") {
        tasks.named("check") {
            dependsOn(ktlintCheck)
        }
    }
}
