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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.ApworldRemoval
import net.eratiem.randogeddon.shared.ApworldResult
import net.eratiem.randogeddon.shared.ApworldResults
import net.eratiem.randogeddon.shared.GenerationProgress
import net.eratiem.randogeddon.shared.ApworldTry
import net.eratiem.randogeddon.shared.ApworldUpload
import net.eratiem.randogeddon.shared.ArchipelagoInfo
import net.eratiem.randogeddon.shared.CommandRequest
import net.eratiem.randogeddon.shared.GameServerState
import net.eratiem.randogeddon.shared.GenerateRequest
import net.eratiem.randogeddon.shared.SeedInfo
import net.eratiem.randogeddon.shared.SeedInstall
import net.eratiem.randogeddon.shared.SeedUpload
import net.eratiem.randogeddon.shared.GenerateResult
import net.eratiem.randogeddon.shared.LogLines
import net.eratiem.randogeddon.shared.MinecraftLogs
import net.eratiem.randogeddon.shared.MinecraftServers
import net.eratiem.randogeddon.shared.AvailableAnswer
import net.eratiem.randogeddon.shared.AvailableRequest
import net.eratiem.randogeddon.shared.SessionServers
import net.eratiem.randogeddon.shared.SlotFile
import net.eratiem.randogeddon.shared.SlotFiles
import org.koin.core.annotation.Single
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

private val VERSIONS_KEPT = 30.seconds

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
    if (!response.status.isSuccess()) unexpected(response)
    return ApiJson.decodeFromString(GameServerState.serializer(), response.bodyAsText())
  }

  suspend fun log(id: String, since: Long): LogLines = call {
    http.get("${session(id)}/server/log?since=$since") { auth() }
  }.let { ApiJson.decodeFromString(LogLines.serializer(), it) }

  @Volatile
  private var versionsSeen: Pair<TimeMark, List<String>>? = null

  /**
   * The Archipelago releases the gateway has, newest first. They change only with the gateway's image, so the answer
   * is kept for [VERSIONS_KEPT] (the panel's overview asks for it every 15 s).
   */
  suspend fun versions(): List<String> {
    versionsSeen?.let { (at, versions) -> if (at.elapsedNow() < VERSIONS_KEPT) return versions }
    return call { http.get("${config.gameServerApi}/internal/versions") { auth() } }
      .let { ApiJson.decodeFromString(ListSerializer(String.serializer()), it) }
      .also { versionsSeen = TimeSource.Monotonic.markNow() to it }
  }

  /** The gateway's own version. */
  suspend fun gatewayVersion(): String = call { http.get("${config.gameServerApi}/internal/version") { auth() } }.trim()

  /** Generates session [id]'s seed (served once its game server starts). Takes up to minutes. */
  suspend fun generate(id: String, request: GenerateRequest): GenerateResult = call {
    // As long as the gateway lets it take (GENERATION_MINUTES), and then some: it answers when it stops one
    postJson("${session(id)}/generate", GenerateRequest.serializer(), request, timeoutMs = 4 * 60 * 60_000L)
  }.let { ApiJson.decodeFromString(GenerateResult.serializer(), it) }

  /** Session [id]'s last generation's log lines after [since] (a part of it at a time); `null`: none. */
  suspend fun generationLog(id: String, since: Long): LogLines? =
    optional { http.get("${session(id)}/generate/log?since=$since&max=1000") { auth() } }
      ?.let { ApiJson.decodeFromString(LogLines.serializer(), it.bodyAsText()) }

  /** Session [id]'s last generation's whole log as text (what ran, then its output); `null`: none. */
  suspend fun generationLogText(id: String): String? =
    optional { http.get("${session(id)}/generate/log.txt") { auth() } }?.bodyAsText()

  /** A seed generated elsewhere: the gateway checks it and keeps it for its session (its refusal says why not). */
  suspend fun uploadSeed(upload: SeedUpload): SeedInfo = library {
    postJson("${config.gameServerApi}/internal/seeds", SeedUpload.serializer(), upload, timeoutMs = 5 * 60_000L)
  }.let { ApiJson.decodeFromString(SeedInfo.serializer(), it) }

  /** The uploaded seed kept under [token] won't become a session: the gateway deletes it (if it still has it). */
  suspend fun dropSeed(token: String) {
    request { http.delete("${config.gameServerApi}/internal/seeds/${token.encodeURLPathPart()}") { auth() } }
  }

  /** The uploaded seed kept under [token] becomes session [id]'s; `null`: the gateway has no such one (any more). */
  suspend fun installSeed(id: String, token: String): SeedInfo? =
    optional { postJson("${session(id)}/seed", SeedInstall.serializer(), SeedInstall(token)) }
      ?.let { ApiJson.decodeFromString(SeedInfo.serializer(), it.bodyAsText()) }

  /** Session [id]'s stopped spoiler log is tried again; `false`: it didn't stop (or goes on already). */
  suspend fun retrySpoilerRun(id: String): Boolean =
    done(HttpStatusCode.Conflict, HttpStatusCode.NotFound) { http.post("${session(id)}/spoiler-run/retry") { auth() } }

  /** Session [id]'s spoiler log's background run: its log lines after [since]; `null`: none. */
  suspend fun spoilerRunLog(id: String, since: Long): LogLines? =
    optional { http.get("${session(id)}/spoiler-run/log?since=$since") { auth() } }
      ?.let { ApiJson.decodeFromString(LogLines.serializer(), it.bodyAsText()) }

  /** Session [id]'s slot [slot] has a new password: the gateway reads it at once and closes the slot's connections. */
  suspend fun disconnectSlot(id: String, slot: String) {
    done { http.post("${session(id)}/slots/${slot.encodeURLPathPart()}/disconnect") { auth() } }
  }

  /** Session [id]'s Minecraft Dig slots' hosted servers. */
  suspend fun minecraft(id: String): MinecraftServers = call { http.get("${session(id)}/minecraft") { auth() } }
    .let { ApiJson.decodeFromString(MinecraftServers.serializer(), it) }

  /** Starts session [id]'s slot [slot]'s Minecraft server; `null`, or why it can't. */
  suspend fun startMinecraft(id: String, slot: String): String? {
    val response = request { http.post("${session(id)}/minecraft/${slot.encodeURLPathPart()}/start") { auth() } }
    return when {
      response.status.isSuccess() -> null
      response.status == HttpStatusCode.Conflict ->
        runCatching { ApiJson.decodeFromString(ApiError.serializer(), response.bodyAsText()).message }.getOrDefault("It can't start")
      else -> unexpected(response)
    }
  }

  /** Stops session [id]'s slot [slot]'s Minecraft server (it saves its world first); `false`: no such slot. */
  suspend fun stopMinecraft(id: String, slot: String): Boolean =
    done(HttpStatusCode.NotFound) { http.post("${session(id)}/minecraft/${slot.encodeURLPathPart()}/stop") { auth() } }

  /** Session [id]'s Minecraft servers' log lines after [since], by slot. */
  suspend fun minecraftLog(id: String, since: Long): MinecraftLogs = call { http.get("${session(id)}/minecraft/log?since=$since") { auth() } }
    .let { ApiJson.decodeFromString(MinecraftLogs.serializer(), it) }

  /** Session [id]'s logic: each asked slot's available checks; `null`: it isn't running. */
  suspend fun available(id: String, request: AvailableRequest): AvailableAnswer? {
    val response = request { postJson("${session(id)}/logic/available", AvailableRequest.serializer(), request) }
    return when {
      response.status == HttpStatusCode.NoContent -> null
      response.status.isSuccess() -> ApiJson.decodeFromString(AvailableAnswer.serializer(), response.bodyAsText())
      else -> unexpected(response)
    }
  }

  /** Session [id]'s logic is built again from the start; `false`: it can have none. */
  suspend fun rebuildLogic(id: String): Boolean =
    done(HttpStatusCode.NotFound) { http.post("${session(id)}/logic/rebuild") { auth() } }

  /** Session [id]'s logic's log lines after [since]; `null`: none. */
  suspend fun logicLog(id: String, since: Long): LogLines? =
    optional { http.get("${session(id)}/logic/log?since=$since") { auth() } }
      ?.let { ApiJson.decodeFromString(LogLines.serializer(), it.bodyAsText()) }

  /** Ends session [id]'s generation; `false`: none runs there (not yet, or no more). */
  suspend fun cancelGeneration(id: String): Boolean =
    done(HttpStatusCode.NotFound) { http.delete("${session(id)}/generate") { auth() } }

  /** How far session [id]'s generation is; `null`: none runs. */
  suspend fun generationProgress(id: String): GenerationProgress? = call { http.get("${session(id)}/progress") { auth() } }
    .takeIf { it.isNotBlank() }?.let { ApiJson.decodeFromString(GenerationProgress.serializer(), it) }

  /** The releases with their games, and the apworld library. The first listing of a release takes seconds. */
  suspend fun archipelago(): ArchipelagoInfo = call {
    http.get("${config.gameServerApi}/internal/archipelago") { auth(); timeout { requestTimeoutMillis = 4 * 60_000 } }
  }.let { ApiJson.decodeFromString(ArchipelagoInfo.serializer(), it) }

  /** Adds apworlds to a release's library; lists the release again (once for all), so it takes seconds. */
  suspend fun uploadApworlds(upload: ApworldUpload): ApworldResults = library {
    postJson("${config.gameServerApi}/internal/apworlds", ApworldUpload.serializer(), upload, timeoutMs = 4 * 60_000L)
  }.let { ApiJson.decodeFromString(ApworldResults.serializer(), it) }

  suspend fun tryApworld(request: ApworldTry): ApworldResult = library {
    postJson("${config.gameServerApi}/internal/apworlds/try", ApworldTry.serializer(), request, timeoutMs = 4 * 60_000L)
  }.let { ApiJson.decodeFromString(ApworldResult.serializer(), it) }

  suspend fun removeApworlds(removal: ApworldRemoval) {
    library {
      val url = "${config.gameServerApi}/internal/apworlds/remove"
      postJson(url, ApworldRemoval.serializer(), removal, timeoutMs = 4 * 60_000L)
    }
  }

  /** Stops session [id]'s game server; its files go to the gateway's `old/`, or for good ([purge]). */
  suspend fun delete(id: String, purge: Boolean): Boolean =
    done(HttpStatusCode.NotFound) { http.delete("${session(id)}${if (purge) "?purge=true" else ""}") { auth() } }

  /** The session's spoiler log; `null` if there is none (generated without one, or its files are gone). */
  suspend fun spoiler(id: String): String? = optional { http.get("${session(id)}/spoiler") { auth() } }?.bodyAsText()

  /** Every slot's files in the session's seed (patches, mods); empty without a seed. */
  suspend fun slotFiles(id: String): List<SlotFile> = call { http.get("${session(id)}/files") { auth() } }
    .let { ApiJson.decodeFromString(SlotFiles.serializer(), it).files }

  /** One of [slotFiles], its bytes; `null` if the seed has no such file. */
  suspend fun slotFile(id: String, name: String): ByteArray? =
    optional { http.get("${session(id)}/files/${name.encodeURLPathPart()}") { auth() } }?.readRawBytes()

  /**
   * `false` if no game server is running to take it. A line with a control character (a line break would be a second
   * command) is refused, as the route refuses it.
   */
  suspend fun command(id: String, line: String): Boolean {
    if (line.any { it.isISOControl() }) throw SessionProblem(HttpStatusCode.BadRequest, Problem.COMMAND_CONTROL)
    return done(HttpStatusCode.Conflict) {
      postJson("${session(id)}/server/command", CommandRequest.serializer(), CommandRequest(line))
    }
  }

  private fun io.ktor.client.request.HttpRequestBuilder.auth() = header("Authorization", "Bearer ${config.internalToken}")

  /** [value] as JSON to [url], with the internal token; [timeoutMs]: longer than the client's own. */
  private suspend fun <T> postJson(url: String, serializer: KSerializer<T>, value: T, timeoutMs: Long? = null): HttpResponse =
    http.post(url) {
      auth()
      if (timeoutMs != null) timeout { requestTimeoutMillis = timeoutMs }
      contentType(ContentType.Application.Json)
      setBody(ApiJson.encodeToString(serializer, value))
    }

  /** An answer the gateway shouldn't give. */
  private fun unexpected(response: HttpResponse): Nothing =
    throw GameServerUnavailable(HttpStatusCode.BadGateway, "The gateway answered ${response.status}")

  /** The gateway's answer; `null` for a 404 (there is none of it). */
  private suspend fun optional(block: suspend () -> HttpResponse): HttpResponse? {
    val response = request(block)
    if (response.status == HttpStatusCode.NotFound) return null
    if (!response.status.isSuccess()) unexpected(response)
    return response
  }

  /** Whether it was done: `false` for the answers [no] (it couldn't be), `true` for a success. */
  private suspend fun done(vararg no: HttpStatusCode, block: suspend () -> HttpResponse): Boolean {
    val response = request(block)
    return when {
      response.status in no -> false
      response.status.isSuccess() -> true
      else -> unexpected(response)
    }
  }

  /** A library change the gateway refuses (400) is a [SessionProblem] with its reason. */
  private suspend fun library(block: suspend () -> HttpResponse): String {
    val response = request(block)
    if (response.status == HttpStatusCode.BadRequest) {
      val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), response.bodyAsText()).message }.getOrNull()
      throw SessionProblem(HttpStatusCode.BadRequest, Refusal(Problem.GATEWAY_REFUSED, detail = message))
    }
    if (!response.status.isSuccess()) unexpected(response)
    return response.bodyAsText()
  }

  private suspend fun call(block: suspend () -> HttpResponse): String {
    val response = request(block)
    if (!response.status.isSuccess()) unexpected(response)
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
