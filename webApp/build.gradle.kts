import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
}

/**
 * The dashboard as a Compose Multiplatform web app (Kotlin/Wasm), installable as a PWA on Android and iOS. The UI is in
 * commonMain and only uses Compose foundation/ui, so other targets can be added later; wasmJsMain holds the browser
 * glue (EventSource, fetch, localStorage).
 */
kotlin {
  jvmToolchain(25)

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName = "webApp"
    browser {
      commonWebpackConfig {
        outputFileName = "webApp.js"
        // `wasmJsBrowserDevelopmentRun`: the API comes from a tracker server running locally on port 8000
        devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
          proxy = mutableListOf(
            KotlinWebpackConfig.DevServer.Proxy(context = mutableListOf("/api", "/healthz"), target = "http://localhost:8000")
          )
        }
      }
    }
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":shared"))
      implementation(libs.bundles.compose)
      implementation(libs.bundles.kotlin.coroutines)
    }
    wasmJsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }
  }
}
