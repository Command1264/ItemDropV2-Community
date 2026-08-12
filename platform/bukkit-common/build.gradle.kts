plugins {
    id("itemdrop.kotlin-jvm8")
}

dependencies {
    api(project(":core"))
    implementation(libs.gson)
    compileOnly(libs.spigot.api)
    compileOnly(libs.placeholder.api)
    testImplementation(libs.spigot.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.sqlite.jdbc)
}
