plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktor)
  alias(libs.plugins.koin)
  alias(libs.plugins.graalvm.native)
}

dependencies {
  implementation(project(":shared"))
  implementation(project(":database"))
  implementation(libs.bundles.ktor.server)
  implementation(libs.bundles.ktor.server.login)
  // Argon2id for the login passwords (pure Java, so it works in the native image)
  implementation(libs.bouncycastle)
  // The QR code for setting up two-factor login (one plain Java class)
  implementation(libs.qrcodegen)
  implementation(libs.bundles.ktor.client)
  implementation(libs.bundles.kotlin.coroutines)
  implementation(libs.kaml)
  implementation(libs.bundles.koin)
  implementation(libs.koin.ktor)
  implementation(libs.slf4j.api)
  implementation(libs.bundles.logging.runtime)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.server.test.host)
}

// Native executable (Linux x86_64), set up in the root build
graalvmNative {
  binaries {
    named("main") {
      imageName.set("randogeddon")
    }
  }
}

// `./gradlew :server:run`: against a gateway on localhost (`:gateway:run`), with the web app of this build and the
// sessions of ./data
tasks.named<JavaExec>("run") {
  workingDir = rootDir
  dependsOn(":webApp:wasmJsBrowserDistribution")
  environment("DATA_DIR", System.getenv("DATA_DIR") ?: "data")
  environment("STATE_DIR", System.getenv("STATE_DIR") ?: "build/tracker-state")
}
