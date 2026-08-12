import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("itemdrop.kotlin-jvm8")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":plugin-bootstrap"))
    runtimeOnly(project(":capability:virtual-stacking-community"))
    runtimeOnly(project(":platform:view-community"))
}

val artifactFileName = "ItemDropV2-Community-${project.version}.jar"
val gitCommitFull = rootProject.extra["itemdropGitCommitFull"].toString()
val gitCommitShort = rootProject.extra["itemdropGitCommitShort"].toString()
val gitDirty = rootProject.extra["itemdropGitDirty"].toString()

tasks.named<ShadowJar>("shadowJar") {
    inputs.property("gitCommitFull", gitCommitFull)
    inputs.property("gitCommitShort", gitCommitShort)
    inputs.property("gitDirty", gitDirty)
    archiveFileName.set(artifactFileName)
    manifest {
        attributes(
            "Implementation-Title" to "ItemDropV2",
            "Implementation-Version" to project.version.toString(),
            "Git-Commit" to gitCommitFull,
            "Git-Commit-Short" to gitCommitShort,
            "Git-Dirty" to gitDirty,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("kotlin", "com.github.command1264.itemdropv2.internal.kotlin")
    relocate("com.google.gson", "com.github.command1264.itemdropv2.internal.gson")
    relocate("org.bstats", "com.github.command1264.itemdropv2.internal.bstats")
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
}

tasks.register<Sync>("stageDistribution") {
    group = "distribution"
    description = "準備 ItemDropV2 Community 成品供 root task 收集。"
    dependsOn(tasks.named("shadowJar"))
    from(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("staged-distribution"))
}

tasks.named("assemble") {
    dependsOn("stageDistribution")
}
