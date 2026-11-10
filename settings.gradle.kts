rootProject.name = "Archipelago"

pluginManagement {
  repositories {
    maven {
      name = "empirerp"
      url = uri("https://nexus.empirerp.de/repository/public/")
    }
  }
}

include(":shared")
include(":server")
include(":webApp")
