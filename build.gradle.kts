import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension

plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.ktor) apply false
  alias(libs.plugins.koin) apply false
  alias(libs.plugins.graalvm.native) apply false
  idea
}

group = "net.eratiem.randogeddon"
// 0.<phase>.<step>: a PR's work is <step>-SNAPSHOT, its last commit before the merge the release (see README, versions)
version = "0.8.4-SNAPSHOT"

allprojects {
  repositories {
    maven {
      name = "example"
      url = uri("https://pkg.example.org/maven/public/")
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

  pluginManager.withPlugin("org.graalvm.buildtools.native") { configureNativeImage() }
}

/**
 * Native executable for Linux x86_64 (glibc), the same setup as CoreSync's server; the modules only name the binary.
 * x86-64-v3 is the right level for the server's Intel i7-8700 (Coffee Lake: AVX2, FMA, BMI2, no AVX-512, so x86-64-v4
 * would not run); pass -PnativeMarch=native only when building on that machine.
 */
fun Project.configureNativeImage() {
  val nativeMarch = providers.gradleProperty("nativeMarch").getOrElse("x86-64-v3")
  val nativeOptimization = providers.gradleProperty("nativeOptimization")
    .orElse(if (version.toString().endsWith("-SNAPSHOT")) "quick" else "release")
    .get()
  val nativeOptimizationArg = when (nativeOptimization) {
    "quick" -> "-Ob"
    "release" -> "-O3"
    else -> throw GradleException("Unsupported nativeOptimization '$nativeOptimization'; use 'quick' or 'release'.")
  }

  extensions.configure<GraalVMExtension> {
    toolchainDetection = false
    // Shared reachability metadata for the libraries (Netty, Logback, ...); an extension of graalvmNative, so in this
    // script it is configured by name
    (this as ExtensionAware).extensions.configure<GraalVMReachabilityMetadataRepositoryExtension>("metadataRepository") {
      enabled = true
    }
    binaries {
      named("main") {
        mainClass.set("$group.ApplicationKt")
        buildArgs.addAll(
          // Include full stack trace in exceptions for better debugging
          "-H:+ReportExceptionStackTraces",
          // Include all character sets (UTF-8, ISO-8859-1, etc.) for text encoding support
          "-H:+AddAllCharsets",
          "-march=$nativeMarch",
          nativeOptimizationArg,
          // Mostly static: links everything except glibc, so the target only needs a compatible glibc.
          "--static-nolibc",
          // Netty loads its native transport; without this, every start warns about restricted methods
          "--enable-native-access=ALL-UNNAMED",
          // SIGTERM (docker stop) runs the shutdown hooks: the tracker saves its history, the gateway lets MultiServer
          // save the game. Without this, a native image as PID 1 ignores SIGTERM and is killed after the grace period
          "--install-exit-handlers",
          // Flyway builds https help links when it starts. GraalVM 25.0.4 enables the URL handlers from the metadata
          // by itself, but the Docker build image (native-image-community:25) is 25.0.2, which needs this
          "--enable-url-protocols=http,https",
        )
      }
    }
    // `./gradlew -Pagent=standard :<module>:run`, then `:<module>:metadataCopy`
    agent {
      metadataCopy {
        inputTaskNames.add("run")
        outputDirectories.add("src/main/resources/META-INF/native-image/${rootProject.group}/$name")
        mergeWithExisting = true
      }
    }
  }

  tasks.named("nativeCompile") {
    doFirst {
      if (!System.getProperty("os.name").startsWith("Linux")) {
        logger.warn("The native image targets Linux x86_64; build it on Linux (the Docker build, or WSL Debian).")
      }
    }
  }
}

idea {
  module {
    isDownloadJavadoc = true
    isDownloadSources = true
  }
}
