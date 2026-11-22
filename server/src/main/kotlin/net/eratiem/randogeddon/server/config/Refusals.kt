package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import java.lang.invoke.MethodHandles
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.admin.GameServerUnavailable
import net.eratiem.randogeddon.server.admin.SessionProblem
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.i18n.English
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.shared.i18n.texts

private val logger = logger(MethodHandles.lookup().lookupClass())

/** The cookie the web app and index.html keep the language in (its code). */
const val LANGUAGE_COOKIE = "ap-language"

/** The language a request is answered in: the app's cookie, else the browser's Accept-Language, else English. */
fun ApplicationCall.language(): Language =
  Language.of(request.cookies[LANGUAGE_COOKIE])
    ?: runCatching { request.acceptLanguageItems() }.getOrDefault(emptyList()).firstNotNullOfOrNull { Language.of(it.value) }
    ?: Language.DEFAULT

/** [refusal] for the answer: worded in the request's language, with its code and values for the web app. */
fun ApplicationCall.apiError(refusal: Refusal): ApiError =
  ApiError(texts(language()).problems.text(refusal), refusal.problem, refusal.args, refusal.detail)

/** Answers [status] with [refusal]. */
suspend fun ApplicationCall.refuse(status: HttpStatusCode, refusal: Refusal) = respond(status, apiError(refusal))

/** In English, for an exception's message and the log; with what another program said below it. */
fun Refusal.english(): String = English.problems.text(this) + (detail?.let { "\n$it" } ?: "")

/** [this] as an answer's part (a new session's YAML problems): in English, with its code and values. */
fun Refusal.toApiError(): ApiError = ApiError(English.problems.text(this), problem, args, detail)

/**
 * Runs [block], answering what's wrong with the request instead of failing it: an account's or a session's problem
 * (400, 403, 404, 409, 410, 422, 429, 502) with its refusal, and a gateway that doesn't answer with
 * [gatewayRefusal].
 */
internal suspend fun RoutingContext.apiCall(block: suspend () -> Unit) {
  try {
    block()
  } catch (e: AccountProblem) {
    call.refuse(e.status, e.refusal)
  } catch (e: SessionProblem) {
    call.refuse(e.status, e.refusal)
  } catch (e: GameServerUnavailable) {
    call.refuse(e.status, call.gatewayRefusal(e))
  }
}

/**
 * The gateway can't be reached; what its client said (it names internal hosts and ports) only for those who run the
 * session ([seesGatewayDetail]), logged for everyone.
 */
internal fun ApplicationCall.gatewayRefusal(e: GameServerUnavailable): Refusal {
  logger.warn("Game server control failed: {}", e.message)
  return Refusal(Problem.GATEWAY_UNREACHABLE, detail = e.message.takeIf { seesGatewayDetail })
}
