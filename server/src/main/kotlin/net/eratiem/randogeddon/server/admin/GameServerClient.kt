package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
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
import io.ktor.client.statement.readRawBytes
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
import net.eratiem.randogeddon.shared.ApworldRemoval
import net.eratiem.randogeddon.shared.ApworldResult
import net.eratiem.randogeddon.shared.ApworldResults
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ArchipelagoInfo
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.SessionServers
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.SlotFiles
import org.koin.core.annotation.Single

/** The gateway didn't answer as expected; [status] for the admin panel (502 when it can't be reached at all). */
class GameServerUnavailable(val status: HttpStatusCode, message: String) : Exception(message)

/**
 * The gateway's internal API (gateway/.../InternalApi.kt): each session's game server (state, start/stop/restart, its
 * log and console), generating a session's seed, deleting its files, its spoiler log and its slots' files; the
 * releases and the apworld library. A stop waits until MultiServer has saved, so the timeout is generous.
 */
@Single
class GameServerClient(private val config: TrackerConfig) {
  private val http = HttpClient(CIO) {
    install(HttpTimeout) { requestTimeoutMillis = 45_000 }
  }

  private fun session(id: String) = "${config.gameServerApi}/internal/sessions/${id.encodeURLPathPart()}"

  /** Every session the gateway has files of, with its game server. */
  suspend fun sessions(): SessionServers = call { http.get("${config.gameServerApi}/internal/sessions") { auth() } }
    .let { ApiJson.decodeFromString(SessionServers.serializer(), it) }

  suspend fun state(id: String): GameServerState = call { http.get("${session(id)}/server") { auth() } }
    .let { ApiJson.decodeFromString(GameServerState.serializer(), it) }

  /** [action]: start, stop or restart; a start without a free game server is a [SessionProblem] (409). */
  suspend fun control(id: String, action: String): GameServerState {
    require(action in setOf("start", "stop", "restart")) { "Unknown action $action" }
    val response = request { http.post("${session(id)}/server/$action") { auth() } }
    if (response.status == HttpStatusCode.Conflict || response.status == HttpStatusCode.NotFound) {
      val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), response.bodyAsText()).message }.getOrNull()
      throw SessionProblem(response.status, Refusal.of(Problem.GATEWAY_ANSWERED, "status" to response.status.value, detail = message))
    }
    if (!response.status.isSuccess()) throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    return ApiJson.decodeFromString(GameServerState.serializer(), response.bodyAsText())
  }

  suspend fun log(id: String, since: Long): LogLines = call {
    http.get("${session(id)}/server/log?since=$since") { auth() }
  }.let { ApiJson.decodeFromString(LogLines.serializer(), it) }

  /** The Archipelago releases the gateway has, newest first. */
  suspend fun versions(): List<String> = call { http.get("${config.gameServerApi}/internal/versions") { auth() } }
    .let { ApiJson.decodeFromString(ListSerializer(String.serializer()), it) }

  /** The gateway's own version. */
  suspend fun gatewayVersion(): String = call { http.get("${config.gameServerApi}/internal/version") { auth() } }.trim()

  /** Generates session [id]'s seed (served once its game server starts). Takes up to minutes. */
  suspend fun generate(id: String, request: GenerateRequest): GenerateResult = call {
    http.post("${session(id)}/generate") {
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

  /** Adds apworlds to a release's library; lists the release again (once for all), so it takes seconds. */
  suspend fun uploadApworlds(upload: ApworldUpload): ApworldResults = library {
    http.post("${config.gameServerApi}/internal/apworlds") {
      auth()
      timeout { requestTimeoutMillis = 4 * 60_000 }
      contentType(ContentType.Application.Json)
      setBody(ApiJson.encodeToString(ApworldUpload.serializer(), upload))
    }
  }.let { ApiJson.decodeFromString(ApworldResults.serializer(), it) }

  suspend fun tryApworld(request: ApworldTry): ApworldResult = library {
    http.post("${config.gameServerApi}/internal/apworlds/try") {
      auth()
      timeout { requestTimeoutMillis = 4 * 60_000 }
      contentType(ContentType.Application.Json)
      setBody(ApiJson.encodeToString(ApworldTry.serializer(), request))
    }
  }.let { ApiJson.decodeFromString(ApworldResult.serializer(), it) }

  suspend fun removeApworlds(removal: ApworldRemoval) {
    library {
      http.post("${config.gameServerApi}/internal/apworlds/remove") {
        auth()
        timeout { requestTimeoutMillis = 4 * 60_000 }
        contentType(ContentType.Application.Json)
        setBody(ApiJson.encodeToString(ApworldRemoval.serializer(), removal))
      }
    }
  }

  /** Stops session [id]'s game server; its files go to the gateway's `old/`, or for good ([purge]). */
  suspend fun delete(id: String, purge: Boolean): Boolean {
    val response = request { http.delete("${session(id)}${if (purge) "?purge=true" else ""}") { auth() } }
    if (response.status == HttpStatusCode.NotFound) return false
    if (!response.status.isSuccess()) throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    return true
  }

  /** The session's spoiler log; `null` if there is none (generated without one, or its files are gone). */
  suspend fun spoiler(id: String): String? {
    val response = request { http.get("${session(id)}/spoiler") { auth() } }
    return when {
      response.status == HttpStatusCode.NotFound -> null
      response.status.isSuccess() -> response.bodyAsText()
      else -> throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    }
  }

  /** Every slot's files in the session's seed (patches, mods); empty without a seed. */
  suspend fun slotFiles(id: String): List<SlotFile> = call { http.get("${session(id)}/files") { auth() } }
    .let { ApiJson.decodeFromString(SlotFiles.serializer(), it).files }

  /** One of [slotFiles], its bytes; `null` if the seed has no such file. */
  suspend fun slotFile(id: String, name: String): ByteArray? {
    val response = request { http.get("${session(id)}/files/${name.encodeURLPathPart()}") { auth() } }
    return when {
      response.status == HttpStatusCode.NotFound -> null
      response.status.isSuccess() -> response.readRawBytes()
      else -> throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")
    }
  }

  /** `false` if no game server is running to take it. */
  suspend fun command(id: String, line: String): Boolean {
    val response = request {
      http.post("${session(id)}/server/command") {
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
      throw SessionProblem(HttpStatusCode.BadRequest, Refusal(Problem.GATEWAY_REFUSED, detail = message))
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
