package net.eratiem.randogeddon.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A game that may have made a generation fail: named in the error, or without a tested apworld. [apworld]: its file,
 * if it isn't bundled; [from]: the release that apworld was uploaded for, if it is another one.
 */
@Serializable
data class Suspect(val game: String, val reason: String, val apworld: String? = null, val from: String? = null)

/** Where a game of a release comes from. */
@Serializable
enum class GameSource {
  /** Part of the Archipelago release (or of the gateway image, like World Locks). */
  @SerialName("bundled") BUNDLED,
  /** An apworld in the server's `custom_worlds/` directory. */
  @SerialName("manual") MANUAL,
  /** An apworld uploaded in the admin panel. */
  @SerialName("uploaded") UPLOADED,
}

/** A game an Archipelago release can generate and serve. */
@Serializable
data class GameInfo(
  val game: String,
  val source: GameSource,
  /** The apworld file, if it isn't bundled. */
  val apworld: String? = null,
  /** Its Python module (`worlds.hk`), to find it in an error. */
  val module: String? = null,
)

/** An apworld that didn't load, and why (as Archipelago says). [name]: the game or the file. */
@Serializable
data class FailedLoad(val name: String, val reason: String)

/** An apworld file of a release's library: uploaded in the admin panel, or put in `custom_worlds/` by hand. */
@Serializable
data class ApworldFile(
  val file: String,
  val release: String,
  val source: GameSource,
  /** Copied from the library of this release ("try with"): not tested with [release]. */
  val from: String? = null,
  val size: Long = 0,
  /** From its `archipelago.json` (if it has one): its game, its own version, the oldest Archipelago release it needs. */
  val game: String? = null,
  val version: String? = null,
  val minimumAp: String? = null,
)

/** One Archipelago release of the gateway: its games, and the apworlds that didn't load. */
@Serializable
data class ReleaseInfo(
  val version: String,
  val games: List<GameInfo> = emptyList(),
  val failed: List<FailedLoad> = emptyList(),
  /** The games couldn't be listed (Python failed). */
  val error: String? = null,
)

/** `GET /api/admin/apworlds`: the gateway's releases (newest first) and the apworld library of each. */
@Serializable
data class ArchipelagoInfo(val releases: List<ReleaseInfo> = emptyList(), val apworlds: List<ApworldFile> = emptyList())

/**
 * `POST /api/admin/apworlds`: apworld files for a release's library, taken in together (the release lists its games
 * once). [replace]: what they would replace (the [ApworldConflict]s of an earlier try) goes. Answered by
 * [ApworldResults].
 */
@Serializable
data class ApworldUpload(val release: String, val files: List<ApworldUploadFile>, val replace: Boolean = false)

/** One file of an [ApworldUpload], base64. */
@Serializable
data class ApworldUploadFile(val fileName: String, val base64: String)

/** What an [ApworldUpload] did: one result per file, in their order. */
@Serializable
data class ApworldResults(val results: List<ApworldResult>)

/** `POST /api/admin/apworlds/remove`: uploaded files out of a release's library, together. */
@Serializable
data class ApworldRemoval(val release: String, val files: List<String>)

/** `POST /api/admin/apworlds/try`: copies an apworld of [from]'s library to [release]'s, to try it there. */
@Serializable
data class ApworldTry(val release: String, val from: String, val file: String)

/**
 * What an upload (or a try) brought: the games the file adds, or why it didn't load; or, with [conflict], that it
 * would replace something and wasn't added (sent again with `replace`, it is).
 */
@Serializable
data class ApworldResult(
  val file: String,
  val games: List<String> = emptyList(),
  val error: String? = null,
  val conflict: ApworldConflict? = null,
)

/** What an upload would replace: a file of the same name in the library, and/or the uploaded [otherFile] of its [game]. */
@Serializable
data class ApworldConflict(val sameFile: Boolean = false, val game: String? = null, val otherFile: String? = null)
