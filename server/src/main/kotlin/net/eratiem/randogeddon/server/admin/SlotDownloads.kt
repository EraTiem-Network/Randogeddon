package net.eratiem.randogeddon.server.admin

import net.eratiem.randogeddon.shared.Problem
import net.eratiem.randogeddon.shared.Refusal
import io.ktor.http.HttpStatusCode
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.server.session.LiveSession
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.session.SeedSlot
import net.eratiem.randogeddon.server.session.Spoilers
import net.eratiem.randogeddon.server.session.seedSlots
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.SessionRole
import net.eratiem.randogeddon.shared.SlotFile
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * Whether someone may download [file]: an [administrator] or a Session-Manager every slot's, a player the files of
 * [mySlots] (the slots they play); support and watchers play none and get nothing.
 */
internal fun mayDownload(administrator: Boolean, role: SessionRole?, mySlots: Collection<String>, file: SlotFile): Boolean =
  administrator || role == SessionRole.MANAGER || (file.slotName != null && file.slotName in mySlots)

/**
 * The seed's files of the slots (patches, mods) as their players download them: each player those of their own slots
 * (a slot played together: each of its players) once the slot is open (a locked slot's wait for its key, like its
 * password), Session-Managers and Administrators every slot's; support and watchers none. For as long as the session exists, finished ones too. The files come from the gateway, which reads
 * them from the seed's zip; their slot numbers get their names from the spoiler log.
 */
@Single
class SlotDownloads(
  private val gameServer: GameServerClient,
  private val sessions: LiveSessions,
  private val spoilers: Spoilers,
) {
  /** The files of session [id] that [user] may download, with their slots' names and games. */
  suspend fun of(id: String, user: User): List<SlotFile> {
    val live = session(id)
    val all = gameServer.slotFiles(live.id)
    if (all.isEmpty()) return emptyList()
    val slots = slots(live)
    val named = all.map { file -> slots[file.slot]?.let { file.copy(slotName = it.name, game = it.game) } ?: file }
    // Still locked (their key not found yet): nothing to start a game with, for who doesn't manage the session
    val manages = user.administrator || live.roleOf(user) == SessionRole.MANAGER
    val locked = if (manages) emptySet() else runCatching {
      live.summary().players.filter { it.lock?.unlocked == false }.map { it.name }.toSet()
    }.getOrDefault(emptySet())
    return named.filter { mayHave(live, user, it) && it.slotName !in locked }
  }

  /** One of [of]'s files, with its bytes; 404 for one [user] may not have, as for one that doesn't exist. */
  suspend fun file(id: String, name: String, user: User): Pair<SlotFile, ByteArray> {
    val file = of(id, user).firstOrNull { it.name == name } ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_FILE)
    val bytes = gameServer.slotFile(id, name) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_FILE)
    logger.info("{} downloaded {} of {}", user.name, name, id)
    return file to bytes
  }

  /** Their own slots' files, and a player's the session's hosted slots' too (to patch the game whose server they join). */
  private fun mayHave(live: LiveSession, user: User, file: SlotFile): Boolean {
    val person = live.personOf(user)
    val hosted = if (person != null) live.setup?.hosted.orEmpty() else emptySet()
    return mayDownload(user.administrator, live.roleOf(user), person?.let { live.people()[it] }.orEmpty() + hosted, file)
  }

  /** The seed's slots by number: from the spoiler log, or else as the tracker knows them (its summary). */
  private suspend fun slots(live: LiveSession): Map<Int, SeedSlot> {
    val fromSpoiler = runCatching { spoilers.of(live.id, live.seedName)?.text?.let(::seedSlots) }.getOrNull().orEmpty()
    if (fromSpoiler.isNotEmpty()) return fromSpoiler
    return runCatching { live.summary().players.associate { it.slot to SeedSlot(it.name, it.game) } }.getOrDefault(emptyMap())
  }

  private fun session(id: String): LiveSession = sessions.get(id) ?: throw SessionProblem(HttpStatusCode.NotFound, Problem.NO_SESSION, "id" to id)
}
