plugins {
    id("itemdrop.kotlin-jvm8")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":platform:view-bukkit"))
    testImplementation(libs.spigot.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
