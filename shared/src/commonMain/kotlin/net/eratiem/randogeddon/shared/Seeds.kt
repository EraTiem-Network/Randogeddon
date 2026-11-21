package net.eratiem.randogeddon.shared

import kotlinx.serialization.Serializable

/**
 * `POST /api/sessions/new/seed` (Administrators), and the tracker's to the gateway: a seed generated elsewhere, the
 * `AP_<seed>.zip` of Archipelago's Launcher (or `Generate.py`), base64. [apVersion]: the release that serves it;
 * `null`: the gateway's newest.
 */
@Serializable
data class SeedUpload(val fileName: String, val base64: String, val apVersion: String? = null)

/** A player's slot of an uploaded seed. */
@Serializable
data class SeedSlot(val name: String, val game: String)

/**
 * What the gateway found in an uploaded seed, which it keeps under [token] for an hour to become a session's
 * ([CreateSessionRequest.seed]): the seed's name, the [release] that serves it and the Archipelago it was
 * [generatedWith], its players' slots, whether it brings a [spoiler] log, and how many [files] for the players
 * (patches) it has.
 */
@Serializable
data class SeedInfo(
  val token: String,
  val seedName: String,
  val release: String,
  val generatedWith: String,
  val slots: List<SeedSlot>,
  val spoiler: Boolean = false,
  val files: Int = 0,
)

/** Tracker → gateway: the uploaded seed kept under [token] becomes the session's. */
@Serializable
data class SeedInstall(val token: String)
