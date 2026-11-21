plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":klass-tree-graph-core"))

    // Analysis API runtime dependencies must remain isolated from the Gradle plugin classpath.
    compileOnly(libs.kotlin.analysis.api)

    testImplementation(libs.kotlin.analysis.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
