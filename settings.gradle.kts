rootProject.name = "Randogeddon"

pluginManagement {
  repositories {
    maven {
      name = "empirerp"
      url = uri("https://nexus.empirerp.de/repository/public/")
    }
  }
}

include(":shared")
include(":database")
include(":server")
include(":gateway")
include(":webApp")
