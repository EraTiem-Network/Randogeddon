package net.eratiem.randogeddon.webapp.api

import kotlinx.serialization.KSerializer
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.ApiJson
import net.eratiem.randogeddon.shared.i18n.texts
import net.eratiem.randogeddon.webapp.app.Languages
import net.eratiem.randogeddon.webapp.app.Platform
import net.eratiem.randogeddon.webapp.app.word

/** POST without a body; the error message, or `null`. */
internal suspend fun post(path: String, body: String? = null): String? =
  (call("POST", path, body) { } as? Answer.Failed)?.message

/** An answer of the admin API: the decoded body, or the error message. */
internal sealed interface Answer<out T> {
  data class Ok<T>(val value: T) : Answer<T>
  /** [detail]: what another program said (Generate.py), shown as it is under [message]. */
  data class Failed(val message: String, val detail: String? = null) : Answer<Nothing>
}

/** [call] for an answer of JSON, decoded with [serializer]. */
internal suspend fun <T> call(method: String, path: String, body: String?, serializer: KSerializer<T>): Answer<T> =
  call(method, path, body) { ApiJson.decodeFromString(serializer, it) }

/**
 * GET [path], decoded with [serializer]; `null` when it can't be had (the server unreachable, a refusal, another
 * shape): for what the page shows when it is there and leaves out otherwise.
 */
internal suspend fun <T> load(path: String, serializer: KSerializer<T>): T? =
  Platform.request("GET", path)?.takeIf { it.status == 200 }
    ?.let { runCatching { ApiJson.decodeFromString(serializer, it.body) }.getOrNull() }

internal suspend fun <T> call(method: String, path: String, body: String?, decode: (String) -> T): Answer<T> {
  val t = texts(Languages.current)
  val result = Platform.request(method, path, body) ?: return Answer.Failed(t.common.serverUnreachable)
  if (result.status in 200..299) return runCatching { Answer.Ok(decode(result.body)) }.getOrElse { Answer.Failed(t.common.unexpectedAnswer) }
  val error = runCatching { ApiJson.decodeFromString(ApiError.serializer(), result.body) }.getOrNull()
  return Answer.Failed(error?.let { t.word(it) } ?: t.common.failed(result.status), error?.detail)
}
