package net.eratiem.randogeddon.server.config

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.eratiem.randogeddon.server.account.PasswordResets
import net.eratiem.randogeddon.shared.ForgotHint
import net.eratiem.randogeddon.shared.ForgotRequest
import net.eratiem.randogeddon.shared.ForgotSendRequest
import net.eratiem.randogeddon.shared.ResetRequest

/**
 * A forgotten password, without a login ([PasswordResets]): `POST /api/login/forgot` (the account's address, partly
 * hidden), `POST /api/login/forgot/send` (its whole address, typed: a link goes there if it is the account's; always
 * 204), `GET /api/login/reset/{token}` (whose the link is) and `POST /api/login/reset` (the new password; it doesn't
 * log in). Rate-limited like the login; the cross-site guard takes the POSTs as it takes every API change.
 */
fun Route.passwordResetRoutes(resets: PasswordResets) {
  rateLimit(LOGIN_RATE_LIMIT) {
    post("/api/login/forgot") {
      apiCall { call.respond(ForgotHint(resets.hint(call.receive<ForgotRequest>().account))) }
    }
    post("/api/login/forgot/send") {
      apiCall {
        val request = call.receive<ForgotSendRequest>()
        resets.ask(request.account, request.email, call.clientKey, call.language())
        call.respond(HttpStatusCode.NoContent)
      }
    }
    get("/api/login/reset/{token}") {
      apiCall { call.respond(resets.view(call.parameters["token"].orEmpty())) }
    }
    post("/api/login/reset") {
      apiCall {
        val request = call.receive<ResetRequest>()
        resets.reset(request.token, request.password)
        call.respond(HttpStatusCode.NoContent)
      }
    }
  }
}
