package net.eratiem.randogeddon.server.admin

import java.lang.invoke.MethodHandles
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import net.eratiem.randogeddon.database.SessionSetup
import net.eratiem.randogeddon.database.SlotPasswordStore
import net.eratiem.randogeddon.server.account.PasswordGenerator
import net.eratiem.randogeddon.server.account.SlotRenewal
import net.eratiem.randogeddon.server.session.LiveSessions
import net.eratiem.randogeddon.server.util.logger
import org.koin.core.annotation.Single

private val logger = logger(MethodHandles.lookup().lookupClass())

/**
 * Session [id]'s [slots] (of the seed [seed]) get new passwords, and the gateway closes their connections (they
 * connect again with the new ones). Every one is renewed and every one told; the first that couldn't be is thrown.
 */
internal suspend fun renewSlots(
  passwords: SlotPasswordStore,
  gameServer: GameServerClient,
  id: String,
  seed: String,
  slots: Collection<String>,
) {
  for (slot in slots) passwords.renew(seed, slot, PasswordGenerator.generate())
  var failed: GameServerUnavailable? = null
  for (slot in slots) {
    try {
      gameServer.disconnectSlot(id, slot)
    } catch (e: GameServerUnavailable) {
      if (failed == null) failed = e
    }
  }
  failed?.let { throw it }
}

/** Whether a client connects as [slot] with its password: not a hosted slot whose server runs elsewhere. */
private fun SessionSetup.connected(slot: String, hosted: Set<String> = this.hosted, direct: Set<String> = this.direct) =
  slot !in hosted || slot in direct

/**
 * The slots open to all of [setup]'s session whose password its players hold, to be renewed when its slot [slot] is
 * played by [after] from now on (names; [open] to all, [hosted], its server run here: [direct]): all of them if
 * someone named for [slot] before plays nothing in the session then (they still hold those passwords); else none.
 */
internal fun openSlotsToRenew(
  setup: SessionSetup,
  slot: String,
  after: Set<String>,
  open: Boolean,
  hosted: Boolean,
  direct: Boolean,
): Set<String> {
  val playing = (setup.worlds + (slot to after.toList())).values.flatten().toSet()
  if (setup.worlds[slot].orEmpty().all { it in playing }) return emptySet()
  val openNow = if (open) setup.open + slot else setup.open - slot
  val hostedNow = if (hosted) setup.hosted + slot else setup.hosted - slot
  val directNow = if (direct) setup.direct + slot else setup.direct - slot
  return openNow.filter { setup.connected(it, hostedNow, directNow) }.toSet()
}

/**
 * The slots of [setup]'s session whose password only the account [account] holds: those naming nobody but it, and the
 * ones open to all if no other player (with an account still) is left. A slot others play too isn't among them.
 */
internal fun slotsOnlyOf(setup: SessionSetup, account: Uuid): Set<String> {
  val name = setup.members[account] ?: return emptySet()
  val own = setup.worlds
    .filter { (slot, names) -> slot !in setup.open && names.toSet() == setOf(name) && setup.connected(slot) }.keys
  val alone = (setup.members.keys - setup.deleted - account).isEmpty()
  return own + if (alone) setup.open.filter { setup.connected(it) } else emptyList()
}

/**
 * An account that can't play any more (kept out, or deleted) leaves the running sessions with nothing to connect: the
 * slots only it plays get new passwords and the gateway closes their connections. A session it fails for is logged.
 */
@Single(binds = [SlotRenewal::class])
class SlotRenewals(
  private val sessions: LiveSessions,
  private val passwords: SlotPasswordStore,
  private val gameServer: GameServerClient,
) : SlotRenewal {
  override suspend fun renewOnlyTheirs(account: Uuid) {
    for (live in sessions.all().filter { !it.finished }) {
      val slots = live.setup?.let { slotsOnlyOf(it, account) }.orEmpty()
      if (slots.isEmpty()) continue
      try {
        renewSlots(passwords, gameServer, live.id, live.seedName, slots)
        logger.info("The slots only {} played in {} have new passwords: {}", account, live.id, slots.sorted().joinToString())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn("Could not renew the passwords of the slots only {} played in {}: {}", account, live.id, e.message)
      }
    }
  }
}
