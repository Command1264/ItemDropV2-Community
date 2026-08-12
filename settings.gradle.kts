pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            name = "spigotSnapshots"
            mavenContent { snapshotsOnly() }
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "paperDependencyMirror"
        }
        maven("https://repo.extendedclip.com/releases/") {
            name = "placeholderApi"
        }
    }
}

rootProject.name = "ItemDropV2-Community"

includeBuild("build-logic")

include(
    ":core",
    ":capability:virtual-stacking-api",
    ":capability:virtual-stacking-community",
    ":platform:bukkit-common",
    ":platform:view-bukkit",
    ":platform:view-community",
    ":plugin-bootstrap",
    ":distribution:community",
)
