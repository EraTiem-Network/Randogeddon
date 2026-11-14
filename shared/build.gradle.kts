import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

/**
 * The API contract between the tracker server and the web app. Kept free of JVM-only libraries, so native Android or
 * iOS targets can be added later.
 */
kotlin {
  jvmToolchain(25)

  jvm()
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.kotlinx.serialization.json)
    }
    // The texts' checks (every language complete, no value dropped) use the JVM's reflection
    jvmTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
