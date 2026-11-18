rootProject.name = "Randogeddon"

pluginManagement {
  // A Maven repository that mirrors Maven Central, the Gradle plugin portal and Google, if there is one: its URL in the
  // Gradle property `randogeddon.maven` (e.g. in ~/.gradle/gradle.properties) or in RANDOGEDDON_MAVEN, its login from
  // a Gradle init script, which may find it by its name (`randogeddon.maven.name`, RANDOGEDDON_MAVEN_NAME; default
  // `mirror`); without one, those three directly
  val mirror = providers.gradleProperty("randogeddon.maven").orElse(providers.environmentVariable("RANDOGEDDON_MAVEN"))
    .orNull?.takeIf { it.isNotBlank() }
  val mirrorName = providers.gradleProperty("randogeddon.maven.name")
    .orElse(providers.environmentVariable("RANDOGEDDON_MAVEN_NAME")).orNull?.takeIf { it.isNotBlank() } ?: "mirror"
  repositories {
    if (mirror != null) {
      maven {
        name = mirrorName
        url = uri(mirror)
      }
    } else {
      gradlePluginPortal()
      mavenCentral()
      google()
    }
  }
}

include(":shared")
include(":database")
include(":server")
include(":gateway")
include(":webApp")
