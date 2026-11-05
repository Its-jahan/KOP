import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM port of web/src/core (contract 1.0.0). No Android dependencies.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The frozen JSON Schema is packaged straight from ../contract (single source of truth).
sourceSets {
    main {
        resources.srcDir(rootProject.file("../contract/schema"))
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "3g"
    systemProperty("contract.dir", rootProject.file("../contract").absolutePath)
    systemProperty("dist.dir", rootProject.file("dist").absolutePath)
    // Optional real-data smoke test: ./gradlew :core:test -PrealOsm=/path/overpass.json
    systemProperty("real.osm", (project.findProperty("realOsm") as String?) ?: "")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
