plugins {
    id("itemdrop.kotlin-jvm8")
}

dependencies {
    api(project(":core"))
    api(project(":platform:bukkit-common"))
    compileOnly(libs.spigot.api)
    testImplementation(libs.spigot.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
