package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguageItems
import io.ktor.server.response.respond
import net.eratiem.randogeddon.shared.ApiError
import net.eratiem.randogeddon.shared.Refusal
import net.eratiem.randogeddon.shared.i18n.English
import net.eratiem.randogeddon.shared.i18n.Language
import net.eratiem.randogeddon.shared.i18n.text
import net.eratiem.randogeddon.shared.i18n.texts

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
