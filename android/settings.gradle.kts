pluginManagement {
    repositories {
        google()
        // Google's official Maven Central mirror first: repo.maven.apache.org / repo1
        // rate-limit (HTTP 429) behind some proxies. Same artifacts, same checksums.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "della-coverage-navigator"
include(":core", ":app")
