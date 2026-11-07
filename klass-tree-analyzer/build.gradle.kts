plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":klass-tree-graph-core"))
    implementation(project(":klass-tree-graph-dot"))

    // The Analysis API is provided by the Gradle plugin's isolated analysis configuration at
    // runtime, so it is not exposed as a transitive dependency of the plugin.
    compileOnly(libs.kotlin.analysis.api)

    testImplementation(libs.kotlin.analysis.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Analysis API creates a few non-daemon helper threads. Keep the test JVM bounded.
    systemProperty("java.awt.headless", "true")
}
