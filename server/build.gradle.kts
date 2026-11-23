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

// The tracker's services on a real PostgreSQL (Testcontainers, needs Docker), only the mails and the gateway faked; as
// the database's own, not part of `test` and the Docker image build: `./gradlew :server:integrationTest`
val integrationTest by sourceSets.creating {
  compileClasspath += sourceSets.main.get().output
  runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
  "integrationTestImplementation"(libs.testcontainers.postgresql)
}
tasks.register<Test>("integrationTest") {
  description = "Runs the tracker's tests against PostgreSQL in a container."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
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
