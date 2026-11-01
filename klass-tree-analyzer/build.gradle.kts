plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":klass-tree-graph-core"))
    implementation(project(":klass-tree-graph-dot"))

    // The Analysis API is provided by the Gradle plugin's isolated analysis configuration at
    // runtime, so it is not exposed as a transitive dependency of the plugin.
    compileOnly("org.jetbrains.kotlin:kotlin-analysis-api:2.5.0-Beta2")

    testImplementation("org.jetbrains.kotlin:kotlin-analysis-api:2.5.0-Beta2")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
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
