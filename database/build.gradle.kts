plugins {
  alias(libs.plugins.kotlin.jvm)
}

/**
 * The PostgreSQL schema (Flyway migrations) and its Exposed tables, shared by the tracker (:server) and the gateway
 * (:gateway). Only the tracker migrates; the gateway reads.
 */
dependencies {
  api(libs.bundles.database)
  implementation(libs.bundles.kotlin.coroutines)
  implementation(libs.slf4j.api)

  testImplementation(kotlin("test"))
}

// Against a real PostgreSQL (Testcontainers, needs Docker), so not part of `test` and the Docker image build:
// `./gradlew :database:integrationTest`
val integrationTest by sourceSets.creating {
  compileClasspath += sourceSets.main.get().output
  runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
  "integrationTestImplementation"(libs.testcontainers.postgresql)
  "integrationTestRuntimeOnly"(libs.bundles.logging.runtime)
}
tasks.register<Test>("integrationTest") {
  description = "Runs the database tests against PostgreSQL in a container."
  group = "verification"
  testClassesDirs = integrationTest.output.classesDirs
  classpath = integrationTest.runtimeClasspath
}
