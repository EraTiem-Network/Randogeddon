import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

/**
 * The project's version as a constant (`RandogeddonVersion.VERSION`), so the tracker, the gateway and the web app
 * each know which build they are (the panel shows them).
 */
val generatedVersion = layout.buildDirectory.dir("generated/version/commonMain/kotlin")
val writeVersion by tasks.registering {
  val version = project.version.toString()
  val out = generatedVersion
  inputs.property("version", version)
  outputs.dir(out)
  doLast {
    val file = out.get().file("net/eratiem/randogeddon/shared/RandogeddonVersion.kt").asFile
    file.parentFile.mkdirs()
    file.writeText(
      """
      |package net.eratiem.randogeddon.shared
      |
      |/** This build's version (root build.gradle.kts), written by the build. */
      |object RandogeddonVersion {
      |  const val VERSION = "$version"
      |}
      |""".trimMargin()
    )
  }
}
tasks.matching { it.name.startsWith("compileKotlin") || it.name.endsWith("SourcesJar") }.configureEach { dependsOn(writeVersion) }

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
    commonMain {
      kotlin.srcDir(generatedVersion)
      dependencies {
        api(libs.kotlinx.serialization.json)
      }
    }
    // The texts' checks (every language complete, no value dropped) use the JVM's reflection
    jvmTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}
