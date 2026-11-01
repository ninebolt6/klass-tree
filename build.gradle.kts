plugins {
    kotlin("jvm") version "2.5.0-Beta2" apply false
    kotlin("plugin.serialization") version "2.5.0-Beta2" apply false
}

allprojects {
    group = "io.github.klass-tree"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
        }
    }
}
