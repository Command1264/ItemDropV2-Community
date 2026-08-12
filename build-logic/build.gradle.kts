plugins {
    `kotlin-dsl`
}

group = "com.github.command1264.itemdropv2.buildlogic"

providers.gradleProperty("community.verification-build-root").orNull?.let { configuredPath ->
    layout.buildDirectory.set(file(configuredPath).resolve("build-logic"))
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
}
