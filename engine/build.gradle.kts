import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run photomosaic engine benchmarks and write engine/build/reports/benchmarks/results.md"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.intrusivethots.mosaic.engine.bench.BenchmarkMainKt")
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("testClasses"))
}
