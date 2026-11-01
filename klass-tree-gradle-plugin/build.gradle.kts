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
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.5.0-Beta2")

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    add(testPluginClasspath.name, "org.jetbrains.kotlin:kotlin-gradle-plugin:2.5.0-Beta2")
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
