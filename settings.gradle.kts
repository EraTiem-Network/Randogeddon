rootProject.name = "Randogeddon"

pluginManagement {
  repositories {
    // Artifact Keeper's `public`: Maven Central, the Gradle plugin portal, Google and the others; the token comes from
    // the Gradle init script (its entry `example`)
    maven {
      name = "example"
      url = uri("https://pkg.example.org/maven/public/")
    }
  }
}

include(":shared")
include(":database")
include(":server")
include(":gateway")
include(":webApp")
