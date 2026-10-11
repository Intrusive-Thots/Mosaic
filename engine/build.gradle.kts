import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("${rootProject.projectDir}/detekt.yml"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    setSource(files("src/main/kotlin", "src/test/kotlin"))
    exclude("**/build/**")
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

tasks.register<JavaExec>("showcase") {
    group = "verification"
    description = "Rebuild showcase docs images. Pass -PshowcaseTheme=naruto, rick, tmnt, koth, or pokemon."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.intrusivethots.mosaic.engine.showcase.ShowcaseMainKt")
    workingDir = rootProject.projectDir
    val theme = (project.findProperty("showcaseTheme") as String?) ?: "naruto"
    val probeDir = project.findProperty("showcaseDir") as String?
    args(if (probeDir == null) listOf(theme) else listOf(theme, probeDir))
    dependsOn(tasks.named("testClasses"))
}

tasks.register<JavaExec>("tune") {
    group = "verification"
    description = "Score cutout collage readability. Args via -PtuneEdge -PtunePieces -PtuneTheme."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.intrusivethots.mosaic.engine.showcase.TuneMainKt")
    workingDir = rootProject.projectDir
    val edge = (project.findProperty("tuneEdge") as String?) ?: "560"
    val pieces = (project.findProperty("tunePieces") as String?) ?: "640"
    val theme = (project.findProperty("tuneTheme") as String?) ?: "all"
    args(edge, pieces, theme)
    dependsOn(tasks.named("testClasses"))
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run photomosaic engine benchmarks and write engine/build/reports/benchmarks/results.md"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.intrusivethots.mosaic.engine.bench.BenchmarkMainKt")
    workingDir = rootProject.projectDir
    dependsOn(tasks.named("testClasses"))
}
