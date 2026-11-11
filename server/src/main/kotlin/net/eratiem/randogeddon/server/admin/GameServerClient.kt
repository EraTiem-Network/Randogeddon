package net.eratiem.randogeddon.server.admin

import io.ktor.client.engine.cio.CIO
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ApworldResult
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ArchipelagoInfo
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.LogLines
import org.koin.core.annotation.Single

/** The gateway didn't answer as expected; [status] for the admin panel (502 when it can't be reached at all). */
class GameServerUnavailable(val status: HttpStatusCode, message: String) : Exception(message)

/**
 * The gateway's internal API (gateway/.../InternalApi.kt): the game server's state, start/stop/restart, its log and
 * console. A stop waits until MultiServer has saved, so the timeout is generous.
 */
@Single
class GameServerClient(private val config: TrackerConfig) {
  private val http = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = 45_000 }
  }

  suspend fun state(): GameServerState = call { http.get("${config.gameServerApi}/internal/server") { auth() } }
    .let { ApiJson.decodeFromString(GameServerState.serializer(), it) }

  /** [action]: start, stop or restart. */
  suspend fun control(action: String): GameServerState {
    require(action in setOf("start", "stop", "restart")) { "Unknown action $action" }
    return call { http.post("${config.gameServerApi}/internal/server/$action") { auth() } }
      .let { ApiJson.decodeFromString(GameServerState.serializer(), it) }
  }

  suspend fun log(since: Long): LogLines = call {
    http.get("${config.gameServerApi}/internal/server/log?since=$since") { auth() }
  }.let { ApiJson.decodeFromString(LogLines.serializer(), it) }

  /** The Archipelago releases the gateway has, newest first. */
  suspend fun versions(): List<String> = call { http.get("${config.gameServerApi}/internal/versions") { auth() } }
    .let { ApiJson.decodeFromString(ListSerializer(String.serializer()), it) }

  /** Generates a seed; it becomes the data directory's seed (served after a restart). Takes up to minutes. */
  suspend fun generate(request: GenerateRequest): GenerateResult = call {
    http.post("${config.gameServerApi}/internal/generate") {
      auth()
      timeout { requestTimeoutMillis = 11 * 60_000 }
      contentType(ContentType.Application.Json)
      setBody(ApiJson.encodeToString(GenerateRequest.serializer(), request))
    }
  }.let { ApiJson.decodeFromString(GenerateResult.serializer(), it) }

  /** The releases with their games, and the apworld library. The first listing of a release takes seconds. */
  suspend fun archipelago(): ArchipelagoInfo = call {
    http.get("${config.gameServerApi}/internal/archipelago") { auth(); timeout { requestTimeoutMillis = 4 * 60_000 } }
  }.let { ApiJson.decodeFromString(ArchipelagoInfo.serializer(), it) }

  /** Adds an apworld to a release's library; lists the release again, so it takes seconds. */
  suspend fun uploadApworld(upload: ApworldUpload): ApworldResult = library {
    http.post("${config.gameServerApi}/internal/apworlds") {
      auth()
      timeout { requestTimeoutMillis = 4 * 60_000 }
      contentType(ContentType.Application.Json)
      setBody(ApiJson.encodeToString(ApworldUpload.serializer(), upload))
    }
  }.let { ApiJson.decodeFromString(ApworldResult.serializer(), it) }

  suspend fun tryApworld(request: ApworldTry): ApworldResult = library {
    http.post("${config.gameServerApi}/internal/apworlds/try") {
      auth()
      timeout { requestTimeoutMillis = 4 * 60_000 }
      contentType(ContentType.Application.Json)
      setBody(ApiJson.encodeToString(ApworldTry.serializer(), request))
    }
  }.let { ApiJson.decodeFromString(ApworldResult.serializer(), it) }

  suspend fun removeApworld(release: String, file: String) {
    library {
      http.delete("${config.gameServerApi}/internal/apworlds/${release.encodeURLPathPart()}/${file.encodeURLPathPart()}") {
        auth()
        timeout { requestTimeoutMillis = 4 * 60_000 }
      }
    }
  }

  /** Stops the game server and moves the seed, its save and room.json away; the files moved. */
  suspend fun deleteSession(): List<String> = call {
    http.post("${config.gameServerApi}/internal/session/delete") { auth() }
  }.let { ApiJson.decodeFromString(ListSerializer(String.serializer()), it) }

  /** `false` if no game server is running to take it. */
  suspend fun command(line: String): Boolean {
    val response = request {
      http.post("${config.gameServerApi}/internal/server/command") {
        auth()
        contentType(ContentType.Application.Json)
        setBody(ApiJson.encodeToString(CommandRequest.serializer(), CommandRequest(line)))
      }
    }
    return when {
      response.status == HttpStatusCode.Conflict -> false
      response.status.isSuccess() -> true
      else -> throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.auth() = header("Authorization", "Bearer ${config.internalToken}")

  /** A library change the gateway refuses (400) is a [SessionProblem] with its reason. */
  private suspend fun library(block: suspend () -> HttpResponse): String {
    val response = request(block)
    if (response.status == HttpStatusCode.BadRequest) {
      val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), response.bodyAsText()).message }.getOrNull()
      throw SessionProblem(HttpStatusCode.BadRequest, message ?: "The gateway refused it")
    }
    if (!response.status.isSuccess()) throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    return response.bodyAsText()
  }

  private suspend fun call(block: suspend () -> HttpResponse): String {
    val response = request(block)
    if (!response.status.isSuccess()) {
      throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    }
    return response.bodyAsText()
  }

  private suspend fun request(block: suspend () -> HttpResponse): HttpResponse = try {
    block()
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: Exception) {
    throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway can't be reached: ${e.message ?: e::class.simpleName}")
  }
}
