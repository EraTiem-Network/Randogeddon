package net.eratiem.randogeddon.server.account

import net.eratiem.randogeddon.database.LoginLinkStore
import net.eratiem.randogeddon.database.User
import net.eratiem.randogeddon.database.UserStore
import net.eratiem.randogeddon.server.config.TrackerConfig
import net.eratiem.randogeddon.server.util.logger
import net.eratiem.randogeddon.shared.UserNames
import org.koin.core.annotation.Single
import java.lang.invoke.MethodHandles
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = logger(MethodHandles.lookup().lookupClass())

/** How long a started login through the provider may take, and a CLI login link works. */
private val PENDING = 10.minutes
val LOGIN_LINK_DURATION = 15.minutes

/** How a login session was made. */
object LoginMethods {
  const val PASSWORD = "password"
  const val OIDC = "oidc"
  const val LINK = "link"
}

/** A login through the provider that isn't for this app: why, as the person reads it. */
class SsoRefused(message: String) : Exception(message)

/** What a login through the provider means for the accounts. */
internal sealed interface SsoAccount {
  /** The account linked to them already. */
  data class Linked(val user: User) : SsoAccount

  /** An account with their verified e-mail address, linked now. */
  data class ByEmail(val user: User) : SsoAccount

  /** No account yet: a new one by this name (made unique). */
  data class New(val name: String) : SsoAccount

  /** Not for them: [demote] an account that was an Administrator and isn't one in the provider any more. */
  data class Refused(val reason: String, val demote: User? = null) : SsoAccount
}

internal const val NOT_AN_ADMINISTRATOR = "Only Randogeddon's Administrators log in this way; players log in with their name and password"

/**
 * Who [identity] is here: only members of [adminGroup] get in this way; their account by the provider's subject
 * ([linked]), the first time by their verified e-mail address ([byEmail]), or a new one. A linked account whose person
 * left the group isn't an Administrator any more.
 */
internal fun ssoAccount(identity: OidcIdentity, adminGroup: String, linked: User?, byEmail: User?): SsoAccount {
  val member = adminGroup in identity.groups
  return when {
    linked != null && member -> SsoAccount.Linked(linked)
    linked != null -> SsoAccount.Refused(NOT_AN_ADMINISTRATOR, demote = linked.takeIf { it.administrator })
    !member -> SsoAccount.Refused(NOT_AN_ADMINISTRATOR)
    byEmail != null && identity.emailVerified -> SsoAccount.ByEmail(byEmail)
    else -> SsoAccount.New(wantedName(identity))
  }
}

/** A name for a new account: the provider's username (or name, or the address's part before the `@`), fit for here. */
internal fun wantedName(identity: OidcIdentity): String {
  val raw = listOfNotNull(identity.preferredUsername, identity.name, identity.email?.substringBefore('@'))
    .firstOrNull { it.isNotBlank() } ?: "Administrator"
  val clean = raw.replace("@", "").filter { !it.isISOControl() }.trim().take(UserNames.MAX_LENGTH)
  return clean.takeIf(UserNames::valid) ?: "Administrator"
}

/** [wanted], or with a number behind it if taken ([taken]): `Ada`, `Ada 2`, … */
internal suspend fun uniqueName(wanted: String, taken: suspend (String) -> Boolean): String {
  if (!taken(wanted)) return wanted
  for (n in 2..999) {
    val candidate = "${wanted.take(UserNames.MAX_LENGTH - 4)} $n"
    if (!taken(candidate)) return candidate
  }
  error("No free name for $wanted")
}

/**
 * Administrators' logins without a password: through the OpenID Connect provider (Authentik) when it is configured,
 * and one-time links from the host's CLI (`randogeddon admins --login`) for when it can't be reached. A login through
 * the provider is started ([start]: its page, with state, nonce and PKCE kept here for 10 minutes) and finished
 * ([finish]: the checked ID token, the account, the Administrator flag following the provider's group).
 */
@Single
class SingleSignOn(
  private val config: TrackerConfig,
  private val users: UserStore,
  private val links: LoginLinkStore,
) {
  private data class Pending(val nonce: String, val verifier: String, val expiresAt: Long)

  private val pending = ConcurrentHashMap<String, Pending>()
  private val client = config.oidc?.let { OidcClient(it, callbackUrl(config)) }

  /** The provider's name for the login button; `null` without one. */
  val name: String? get() = config.oidc?.name

  val enabled: Boolean get() = client != null

  /** Starts a login: its `state` (for the cookie) and the provider's page to go to. */
  suspend fun start(now: Long = System.currentTimeMillis()): Pair<String, String> {
    val oidc = client ?: throw SsoRefused("No single sign-on here")
    pending.entries.removeIf { it.value.expiresAt <= now }
    val state = newToken()
    val login = Pending(newToken(), newToken(), now + PENDING.inWholeMilliseconds)
    pending[state] = login
    return state to oidc.authorizationUrl(state, login.nonce, login.verifier)
  }

  /** Finishes the login of [state] with the callback's [code]: the account to log in, an Administrator. */
  suspend fun finish(state: String, code: String, now: Long = System.currentTimeMillis()): User {
    val oidc = client ?: throw SsoRefused("No single sign-on here")
    val login = pending.remove(state)?.takeIf { it.expiresAt > now } ?: throw SsoRefused("The login has expired: start it again")
    val identity = try {
      oidc.identity(code, login.nonce, login.verifier)
    } catch (e: OidcProblem) {
      logger.warn("A login through {} was refused: {}", config.oidc?.name, e.message)
      throw SsoRefused("The login didn't work: start it again")
    }
    val group = config.oidc!!.adminGroup
    val linked = users.findBySubject(identity.subject)
    val byEmail = identity.email?.takeIf { identity.emailVerified }?.let { users.findLogin(it) }
    val user = when (val decision = ssoAccount(identity, group, linked, byEmail)) {
      is SsoAccount.Linked -> decision.user
      is SsoAccount.ByEmail -> {
        if (!users.linkSubject(decision.user.id, identity.subject)) throw SsoRefused("${decision.user.name} is linked to another login")
        logger.info("{} is linked to {} now", decision.user.name, config.oidc?.name)
        decision.user
      }
      is SsoAccount.New -> {
        val name = uniqueName(decision.name) { users.findActive(it) != null }
        // No password of their own: they log in through the provider (or a CLI link). Their verified address if free
        val email = identity.email?.takeIf { identity.emailVerified && users.accountByEmail(it) == null }
        val created = users.create(name, PasswordHasher.hash(PasswordGenerator.generate()), email = email)
        users.linkSubject(created.id, identity.subject)
        logger.info("{} came through {}: a new account", name, config.oidc?.name)
        created
      }
      is SsoAccount.Refused -> {
        decision.demote?.let {
          users.setAdmin(it.id, false)
          logger.info("{} is no Administrator any more (not in {})", it.name, group)
        }
        throw SsoRefused(decision.reason)
      }
    }
    if (!user.administrator) users.setAdmin(user.id, true)
    return user.copy(administrator = true)
  }

  /** A one-time login link for [user] (the CLI), for [LOGIN_LINK_DURATION]. */
  suspend fun createLink(user: User, now: Instant = Instant.now()): String = createLoginLink(links, config.publicUrl, user, now)

  /** The user of a login link, which works once; `null` if it doesn't (any more). */
  suspend fun useLink(token: String): User? = links.use(tokenHash(token))?.let { users.findActive(it) }

  companion object {
    fun callbackUrl(config: TrackerConfig) = "${config.publicUrl}/api/login/oidc/callback"
  }
}

/** A one-time login link for [user]: `<publicUrl>/#/login/<token>`; only the token's SHA-256 is stored. */
suspend fun createLoginLink(links: LoginLinkStore, publicUrl: String, user: User, now: Instant = Instant.now()): String {
  val token = newToken()
  links.create(tokenHash(token), user.id, now.plus(LOGIN_LINK_DURATION.toJavaDuration()), now)
  return "$publicUrl/#/login/$token"
}
