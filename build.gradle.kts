import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}

val ktlintCli = configurations.create("ktlintCli")

dependencies {
    ktlintCli("${libs.ktlint.cli.get()}:all@jar")
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
    apply(plugin = "com.vanniktech.maven.publish")

    extensions.configure<MavenPublishBaseExtension> {
        pom {
            name.set(project.name)
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjdk-release=21")
            allWarningsAsErrors.set(true)
        }
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
        }
        tasks.named("check") {
            dependsOn(ktlintCheck)
        }
    }
}
