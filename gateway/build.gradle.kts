plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.graalvm.native)
  application
}

/**
 * The game clients' entry to MultiServer: relays every connection and lets a client connect as a slot only with that
 * slot's password (from PostgreSQL, see :database). Runs on its own, so a tracker restart doesn't drop the players.
 */
dependencies {
  implementation(project(":shared"))
  implementation(project(":database"))
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.config.yaml)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.forwarded.header)
  implementation(libs.bundles.ktor.client)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.bundles.kotlin.coroutines)
  implementation(libs.slf4j.api)
  implementation(libs.bundles.logging.runtime)

  testImplementation(kotlin("test"))
  testImplementation(libs.ktor.server.test.host)
}

application {
  mainClass = "$group.ApplicationKt"
}

// Native executable (Linux x86_64), set up in the root build
graalvmNative {
  binaries {
    named("main") {
      imageName.set("randogeddon-gateway")
    }
  }
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

// `./gradlew :gateway:run`: only relays, to a MultiServer on localhost:38282 (GAME_SERVER=true to run one, with
// AP_HOME pointing to an Archipelago checkout)
tasks.named<JavaExec>("run") {
  workingDir = rootDir
  environment("ROOM_JSON", System.getenv("ROOM_JSON") ?: "data/room.json")
  environment("GAME_SERVER", System.getenv("GAME_SERVER") ?: "false")
  environment("DATA_DIR", System.getenv("DATA_DIR") ?: "data")
}
