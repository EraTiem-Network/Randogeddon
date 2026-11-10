plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.ktor) apply false
  alias(libs.plugins.koin) apply false
  idea
}

group = "net.eratiem.randogeddon"
version = "0.1.0-SNAPSHOT"

allprojects {
  repositories {
    maven {
      name = "empirerp-public"
      url = uri("https://nexus.empirerp.de/repository/public/")
    }
  }
}

subprojects {
  version = rootProject.version

  fun buildGroup(): Pair<String, String> {
    var parent = project.parent?.takeIf { it != rootProject }
    var group = project.name
    var projectPath = project.name

    while (parent != null) {
      group = "${parent.name}.$group"
      projectPath = "${parent.name}:$projectPath"
      parent = parent.parent?.takeIf { it != rootProject }
    }

    group = "${rootProject.group}.$group"

    return projectPath.lowercase() to group.lowercase()
  }

  val (projectPath, groupPackage) = buildGroup()
  group = groupPackage
  logger.info("${projectPath}\n-> $group")
}

idea {
  module {
    isDownloadJavadoc = true
    isDownloadSources = true
  }
}
