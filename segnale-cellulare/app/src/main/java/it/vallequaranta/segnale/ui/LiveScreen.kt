package it.vallequaranta.segnale.ui

import it.vallequaranta.segnale.analysis.formatDistance
import it.vallequaranta.segnale.radio.CellMeasurement
import it.vallequaranta.segnale.radio.Quality
import it.vallequaranta.segnale.radio.Tech
import it.vallequaranta.segnale.ui.UiKit.card
import it.vallequaranta.segnale.ui.UiKit.scroll
import it.vallequaranta.segnale.ui.UiKit.text
import it.vallequaranta.segnale.ui.UiKit.title
import it.vallequaranta.segnale.ui.UiKit.vertical

/** Tutte le celle visibili in questo momento, con i parametri radio. */
class LiveScreen(private val a: MainActivity) : Screen {
    private val status = a.text(size = 13f, color = UiKit.MUTED)
    private val list = a.vertical(0)
    override val view = a.scroll(a.vertical().apply {
        addView(a.title("Celle visibili ora"))
        addView(status)
        addView(list)
        addView(a.text(
            "★ = cella a cui è agganciato il telefono. Le celle vicine senza ID sono " +
                "identificate da canale e PCI; l'operatore è dedotto dalla SIM (\"≈\").\n" +
                "RSRP: ≥ −80 ottimo · −90 buono · −100 discreto · −110 scarso · sotto pessimo.",
            12f, color = UiKit.MUTED,
        ))
    })

    override fun onShown() = render()
    override fun onCells() = render()
    override fun onLocation() = renderStatus()

    private fun renderStatus() {
        val l = a.location
        val gps = when {
            !a.gpsEnabled() -> "GPS SPENTO: attivalo, serve anche per leggere le celle"
            l == null -> "GPS: in attesa del fix…"
            else -> "GPS ±${l.accuracy.toInt()} m"
        }
        val age = if (a.lastScanTime == 0L) "nessuna scansione" else "aggiornato ${(System.currentTimeMillis() - a.lastScanTime) / 1000} s fa"
        status.text = "$gps · ${a.cells.size} celle · $age"
    }

    private fun render() {
        renderStatus()
        list.removeAllViews()
        if (a.cells.isEmpty()) {
            list.addView(a.text("Nessuna cella: controlla permessi, GPS attivo e SIM inserita."))
            return
        }
        for (c in a.cells) list.addView(a.card(Quality.rsrpColor(normalized(c)), a.text(describe(c), 14f)), UiKit.matchWrap())
    }

    companion object {
        /** Porta 2G/3G su una scala simile all'RSRP solo per il colore. */
        fun normalized(c: CellMeasurement): Int = when (c.tech) {
            Tech.LTE, Tech.NR -> c.level
            Tech.UMTS -> c.level - 20     // RSCP ≈ RSRP + ~20 dB a pari copertura
            Tech.GSM -> c.level - 30      // RSSI su 200 kHz
        }

        fun levelName(t: Tech) = when (t) {
            Tech.LTE, Tech.NR -> "RSRP"
            Tech.UMTS -> "RSCP"
            Tech.GSM -> "RSSI"
        }

        fun describe(c: CellMeasurement): String {
            val sb = StringBuilder()
            sb.append(if (c.registered) "★ " else "")
            sb.append("${c.tech.label} ${c.operatorName}")
            if (c.operatorAssumed) sb.append(" ≈")
            c.band?.let { sb.append(" · ${it.name} ${it.commonName}") }
            sb.append("\n${levelName(c.tech)} ${c.level} dBm")
            if (c.tech == Tech.LTE || c.tech == Tech.NR) sb.append(" (${Quality.rsrpLabel(c.level)})")
            c.quality?.let { sb.append(" · ${if (c.tech == Tech.UMTS) "Ec/No" else "RSRQ"} $it dB") }
            c.sinr?.let { sb.append(" · SINR $it dB") }
            val ids = ArrayList<String>()
            when {
                c.tech == Tech.LTE && c.siteId != null -> ids += "eNB ${c.siteId} sett. ${c.sector}"
                c.cellId != null -> ids += "ID ${c.cellId}"
            }
            c.pci?.let { ids += "PCI $it" }
            c.channel?.let { ids += "ch $it" }
            c.tac?.let { ids += (if (c.tech == Tech.LTE || c.tech == Tech.NR) "TAC " else "LAC ") + it }
            c.timingAdvance?.let { ta -> ids += "TA $ta" + (c.timingAdvanceMeters?.let { " (≈${formatDistance(it)})" } ?: "") }
            if (ids.isNotEmpty()) sb.append("\n").append(ids.joinToString(" · "))
            return sb.toString()
        }
    }
}
