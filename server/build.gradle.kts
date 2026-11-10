plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktor)
  alias(libs.plugins.koin)
}

dependencies {
  implementation(project(":shared"))
  implementation(libs.bundles.ktor.server)
  implementation(libs.bundles.ktor.client)
  implementation(libs.bundles.kotlin.coroutines)
  implementation(libs.kaml)
  implementation(libs.bundles.koin)
  implementation(libs.koin.ktor)
  implementation(libs.slf4j.api)
  implementation(libs.bundles.logging.runtime)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
}

application {
  mainClass = "$group.ApplicationKt"
}

kotlin {
  jvmToolchain(25)
}

sourceSets {
  main {
    java.srcDirs.clear()

    kotlin.srcDirs("src/main/kotlin")
  }
}

// `./gradlew :server:run`: against a game server on localhost (docker-compose.local.yml), with the web app of this
// build and the room of ./data
tasks.named<JavaExec>("run") {
  workingDir = rootDir
  dependsOn(":webApp:wasmJsBrowserDistribution")
  environment("ROOM_JSON", System.getenv("ROOM_JSON") ?: "data/room.json")
  environment("STATE_PATH", System.getenv("STATE_PATH") ?: "build/tracker-state/tracker.json")
  environment("SESSION_YAML", System.getenv("SESSION_YAML") ?: "data/session.yaml")
}
