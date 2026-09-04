plugins {
    id("buildsrc.convention.kotlin-jvm")
}

repositories {
    mavenCentral()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))

    implementation(libs.kotlinxCoroutines)
    implementation(libs.kotlinxCoroutinesSwing)
    implementation(libs.kotlinxSerialization)

    implementation(libs.bundles.flatlaf)
    implementation(libs.jsvg)
    implementation(libs.miglayout)

    implementation(libs.jnativehook)
    // Arrives transitively with jnativehook, but SystemKeyboardLayout calls it directly,
    // and a dependency you compile against belongs in the build file that compiles it.
    implementation(libs.jnaPlatform)
    implementation(libs.jkeymaster)

    implementation(libs.commonmark)

    testImplementation(kotlin("test"))
}
