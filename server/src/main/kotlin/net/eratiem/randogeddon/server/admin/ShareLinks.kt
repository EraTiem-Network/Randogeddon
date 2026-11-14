package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.database.ShareLinkStore
import net.eratiem.randogeddon.server.account.AccountProblem
import net.eratiem.randogeddon.server.account.newToken
import net.eratiem.randogeddon.server.account.tokenHash
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.ShareLinkView
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * A session's share link: whoever has it follows the session without a login, read-only (for a stream): the players'
 * summary, no passwords, no spoiler, no connection. Made and turned off by its players and Session-Managers; a new
 * link replaces the old one. The token is shown once and stored only as its SHA-256.
 */
@Single
class ShareLinks(private val store: ShareLinkStore, private val sessions: LiveSessions) {
  suspend fun view(id: String): ShareLinkView {
    val live = session(id)
    val link = store.of(live.id) ?: return ShareLinkView()
    return ShareLinkView(on = true, createdBy = link.createdBy, createdAt = link.createdAt.toEpochMilli())
  }

  /** A new link (the old one stops working), with its token: the only time it is shown. */
  suspend fun create(id: String, by: String): ShareLinkView {
    val live = session(id)
    val token = newToken()
    if (!store.replace(live.id, tokenHash(token), by)) throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
    logger.info("{} made a new share link for {}", by, live.id)
    return view(live.id).copy(token = token)
  }

  suspend fun remove(id: String, by: String) {
    val live = session(id)
    if (store.remove(live.id)) logger.info("{} turned the share link of {} off", by, live.id)
  }

  /** The session whose link [token] is; `null`: no such link (any more). */
  suspend fun sessionOf(token: String): LiveSession? = store.sessionWith(tokenHash(token))?.let { sessions.get(it) }

  private fun session(id: String): LiveSession = sessions.get(id) ?: throw AccountProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
}
