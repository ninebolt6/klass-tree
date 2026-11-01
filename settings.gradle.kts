pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "klass-tree"

include("klass-tree-graph-core")
include("klass-tree-graph-dot")
include("klass-tree-analyzer")
include("klass-tree-gradle-plugin")
