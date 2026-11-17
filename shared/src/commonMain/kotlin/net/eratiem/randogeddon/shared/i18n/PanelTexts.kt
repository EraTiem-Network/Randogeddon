package net.eratiem.randogeddon.shared.i18n

import net.eratiem.randogeddon.shared.GameServerStatus

/** The panel (*Manage*): its frame, a session's game server, its log and console. */
interface PanelTexts {
  val titleAdministrator: String
  val title: String
  val general: String
  val gameServer: String
  val gatewayUnreachable: String
  fun status(status: GameServerStatus): String
  fun up(duration: String): String
  fun seedFile(name: String): String
  val runByGateway: String
  fun lastExitCode(code: Int): String
  fun restarts(n: Int): String
  val startTip: String
  val start: String
  val stopTip: String
  val stop: String
  val restartTip: String
  val restart: String
  /** Asked before stopping or restarting ([restart]). */
  fun confirm(restart: Boolean): String
  fun yes(restart: Boolean): String
  val log: String
  val logSubtitle: String
  val nothingYet: String
  val console: String
  val consoleSubtitle: String
  val consoleTip: String
  val send: String
}

internal object EnglishPanel : PanelTexts {
  override val titleAdministrator = "Manage · Administrator"
  override val title = "Manage your sessions"
  override val general = "General"
  override val gameServer = "Game server"
  override val gatewayUnreachable = "gateway unreachable"
  override fun status(status: GameServerStatus) = when (status) {
    GameServerStatus.RUNNING -> "running"
    GameServerStatus.STARTING -> "starting"
    GameServerStatus.CRASHED -> "crashed"
    GameServerStatus.STOPPED -> "stopped"
    GameServerStatus.NO_SEED -> "no seed"
  }
  override fun up(duration: String) = "up $duration"
  override fun seedFile(name: String) = "seed file $name"
  override val runByGateway = "MultiServer, run by the gateway"
  override fun lastExitCode(code: Int) = "last exit code $code"
  override fun restarts(n: Int) = "$n ${if (n == 1) "restart" else "restarts"} after crashes"
  override val startTip =
    "It serves the session's seed (not a paused or ended one: reopen that first). The gateway restarts a crashed one by itself."
  override val start = "Start"
  override val stopTip =
    "It saves and ends; every player is disconnected. It stays stopped, and the session time doesn't count, until started again."
  override val stop = "Stop"
  override val restartTip = "Stop and start, e.g. after changing something by hand."
  override val restart = "Restart"
  override fun confirm(restart: Boolean) = "${if (restart) "Restart" else "Stop"} the game server? Every player is disconnected."
  override fun yes(restart: Boolean) = if (restart) "Yes, restart" else "Yes, stop"
  override val log = "Log"
  override val logSubtitle = "the game server's output, newest on top"
  override val nothingYet = "Nothing yet."
  override val console = "Console"
  override val consoleSubtitle = "server commands, e.g. /players, /send <slot> <item>, /hint <slot> <item>"
  override val consoleTip =
    "Archipelago's server console. Its commands say \"player\" for a slot: /send Alice-HK Mantis Claw gives the " +
      "slot Alice-HK that item. Names with spaces in quotes (/release and /collect take the rest of the line). " +
      "/help lists every command; the answers show in the log."
  override val send = "Send"
}

internal object GermanPanel : PanelTexts {
  override val titleAdministrator = "Verwalten · Administrator"
  override val title = "Deine Sessions verwalten"
  override val general = "Allgemein"
  override val gameServer = "Spielserver"
  override val gatewayUnreachable = "Gateway nicht erreichbar"
  override fun status(status: GameServerStatus) = when (status) {
    GameServerStatus.RUNNING -> "läuft"
    GameServerStatus.STARTING -> "startet"
    GameServerStatus.CRASHED -> "abgestürzt"
    GameServerStatus.STOPPED -> "gestoppt"
    GameServerStatus.NO_SEED -> "kein Seed"
  }
  override fun up(duration: String) = "läuft seit $duration"
  override fun seedFile(name: String) = "Seed-Datei $name"
  override val runByGateway = "MultiServer, vom Gateway betrieben"
  override fun lastExitCode(code: Int) = "letzter Exit-Code $code"
  override fun restarts(n: Int) = "$n ${if (n == 1) "Neustart" else "Neustarts"} nach Abstürzen"
  override val startTip =
    "Er stellt den Seed der Session bereit (nicht den einer pausierten oder beendeten: Öffne diese zuerst wieder). Einen " +
      "abgestürzten startet das Gateway von selbst neu."
  override val start = "Starten"
  override val stopTip =
    "Er speichert und endet; alle Spieler werden getrennt. Er bleibt gestoppt, und die Session-Zeit zählt nicht, " +
      "bis er wieder gestartet wird."
  override val stop = "Stoppen"
  override val restartTip = "Stoppen und starten, z. B. nachdem du etwas von Hand geändert hast."
  override val restart = "Neu starten"
  override fun confirm(restart: Boolean) =
    "Den Spielserver ${if (restart) "neu starten" else "stoppen"}? Alle Spieler werden getrennt."
  override fun yes(restart: Boolean) = if (restart) "Ja, neu starten" else "Ja, stoppen"
  override val log = "Log"
  override val logSubtitle = "die Ausgabe des Spielservers, neueste oben"
  override val nothingYet = "Noch nichts."
  override val console = "Konsole"
  override val consoleSubtitle = "Server-Befehle, z. B. /players, /send <Slot> <Item>, /hint <Slot> <Item>"
  override val consoleTip =
    "Die Server-Konsole von Archipelago. Ihre Befehle sagen \"player\" zu einem Slot: /send Alice-HK Mantis Claw " +
      "gibt dem Slot Alice-HK dieses Item. Namen mit Leerzeichen in Anführungszeichen (/release und /collect nehmen " +
      "den Rest der Zeile). /help listet alle Befehle; die Antworten erscheinen im Log."
  override val send = "Senden"
}
