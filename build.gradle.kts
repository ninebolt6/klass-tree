import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "io.github.ninebolt6.klass-tree"
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
        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
        pom {
            name.set(project.name)
            description.set("Source-level Kotlin dependency graphs for Kotlin JVM projects.")
            url.set("https://github.com/ninebolt6/klass-tree")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("ninebolt6")
                    name.set("ninebolt6")
                    url.set("https://github.com/ninebolt6")
                }
            }
            scm {
                url.set("https://github.com/ninebolt6/klass-tree")
                connection.set("scm:git:https://github.com/ninebolt6/klass-tree.git")
                developerConnection.set("scm:git:ssh://git@github.com/ninebolt6/klass-tree.git")
            }
        }
    }

    extensions.configure<SigningExtension> {
        setRequired(providers.gradleProperty("signingInMemoryKey").isPresent)
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
