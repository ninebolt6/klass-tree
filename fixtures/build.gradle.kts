plugins {
    kotlin("jvm") version "2.5.0-Beta2" apply false
    id("io.github.klass-tree")
}

klassTree {
    includeTests.set(
        providers.gradleProperty("klassTreeTests").map { it.toBoolean() }.orElse(false),
    )
    outputDirectory.set(layout.buildDirectory.dir("klass-tree"))
}
