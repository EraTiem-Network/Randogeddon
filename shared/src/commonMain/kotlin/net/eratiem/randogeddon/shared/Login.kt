package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/invitations/{token}`: whether the link still works, and the name the admin suggested. */
@Serializable
data class InvitationInfo(
  val valid: Boolean,
  val email: String? = null,
  val suggestedName: String? = null,
  /** Why it doesn't work (used, expired, replaced by a newer one), in the request's language, and as a code. */
  val problem: String? = null,
  val code: Problem? = null,
)

/** `POST /api/signup`: the invitation's token, and what the new user chose. Logs them in. */
@Serializable
data class SignupRequest(
  val token: String,
  val name: String,
  val password: String,
  val autoDelete: AutoDelete = AutoDelete.NEVER,
)

/** `POST /api/email/confirm`: the token from the confirmation mail to a new address. */
@Serializable
data class TokenRequest(val token: String)

/** What comes after the password at the login. */
@Serializable
enum class LoginNext {
  /** A code from the authenticator (or a recovery code). */
  @SerialName("code") CODE,
  /** An admin without two-factor login sets it up first. */
  @SerialName("setup") SETUP,
}

/**
 * `POST /api/login` with the right password of someone with two-factor login (or an admin who has to set it up): no
 * cookie yet, but [pending], valid for a few minutes, for the next step.
 */
@Serializable
data class LoginStep(val next: LoginNext, val pending: String)

/** `POST /api/login/code` (with [pending]), or a code that confirms or changes the user's own two-factor login. */
@Serializable
data class CodeRequest(val code: String, val pending: String? = null)

/**
 * `GET /api/login/options`, before the login: [singleSignOn] names the provider Administrators log in through (the
 * login form's button, to `GET /api/login/oidc`); `null`: none. [passwordReset]: a forgotten password can be reset by
 * mail (a mail server is set), so the form shows its link.
 */
@Serializable
data class LoginOptions(val singleSignOn: String? = null, val passwordReset: Boolean = false)

/** `POST /api/login/link`: a one-time login link of the host's CLI (`randogeddon admins --login`). */
@Serializable
data class LinkLoginRequest(val token: String)

/** `POST /api/login/setup`: the pending login of an admin who sets up two-factor login now. */
@Serializable
data class PendingRequest(val pending: String)

/**
 * A new TOTP secret to add to an authenticator app: as a QR code ([svg], or [rows] of `0`/`1` to draw), as the
 * `otpauth://` link it encodes, and as [secret] to type in by hand. It counts once a code from it confirms it.
 */
@Serializable
data class TotpSetup(val secret: String, val uri: String, val svg: String, val rows: List<String> = emptyList())

/** The recovery codes, shown only this once. */
@Serializable
data class RecoveryCodeList(val codes: List<String>)

/** `POST /api/login`. */
@Serializable
data class LoginRequest(val username: String, val password: String)

/**
 * `POST /api/login/forgot`, the first step of a forgotten password: the account's name or e-mail address, at most
 * [MAX_LENGTH] characters.
 */
@Serializable
data class ForgotRequest(val account: String) {
  companion object {
    const val MAX_LENGTH = EmailAddresses.MAX_LENGTH
  }
}

/**
 * The answer to [ForgotRequest]: the account's address partly hidden (`m•••@p•••.me`), for every name or address,
 * one without an account too (made up then, the same each time): it never tells whether there is an account.
 */
@Serializable
data class ForgotHint(val address: String)

/**
 * `POST /api/login/forgot/send`: the account as in [ForgotRequest], and its whole address as the person types it. If
 * it is the account's, a reset link goes there; the answer is the same either way (204).
 */
@Serializable
data class ForgotSendRequest(val account: String, val email: String)

/** `GET /api/login/reset/{token}`: the name of the account whose password the link resets, to greet them. */
@Serializable
data class ResetView(val name: String)

/** `POST /api/login/reset`: the reset link's token and the new password. Doesn't log in; every login ends. */
@Serializable
data class ResetRequest(val token: String, val password: String)
