plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-gradle-plugin`
}

val testPluginClasspath = configurations.create("testPluginClasspath")

dependencies {
    implementation(project(":klass-tree-graph-core"))
    implementation(project(":klass-tree-analyzer"))

    // The Kotlin Gradle plugin is supplied by the consuming build. The plugin only needs the
    // public Gradle API to enumerate Kotlin source sets and compile dependencies.
    compileOnly(libs.kotlin.gradle.plugin.api)

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    add(testPluginClasspath.name, libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("klassTree") {
            id = "io.github.ninebolt6.klass-tree"
            implementationClass = "io.github.ninebolt6.klasstree.gradle.KlassTreePlugin"
            displayName = "klass-tree"
            description = "Outputs a source-level Kotlin dependency graph for a multi-module Gradle build."
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(testPluginClasspath)
}
