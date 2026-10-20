pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        maven {
            name = "Fabric Snapshots"
            url = uri("https://maven.fabricmc.net/snapshots")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "zenan-addon"
