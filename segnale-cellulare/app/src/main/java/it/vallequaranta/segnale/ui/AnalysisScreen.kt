package it.vallequaranta.segnale.ui

import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import it.vallequaranta.segnale.analysis.EntitySummary
import it.vallequaranta.segnale.analysis.Geo
import it.vallequaranta.segnale.analysis.KnownSite
import it.vallequaranta.segnale.analysis.formatDistance
import it.vallequaranta.segnale.radio.Quality
import it.vallequaranta.segnale.radio.Tech
import it.vallequaranta.segnale.ui.UiKit.button
import it.vallequaranta.segnale.ui.UiKit.card
import it.vallequaranta.segnale.ui.UiKit.horizontal
import it.vallequaranta.segnale.ui.UiKit.scroll
import it.vallequaranta.segnale.ui.UiKit.text
import it.vallequaranta.segnale.ui.UiKit.title
import it.vallequaranta.segnale.ui.UiKit.vertical

/** Classifica operatori, tralicci stimati con azimut e consigli per l'impianto. */
class AnalysisScreen(private val a: MainActivity) : Screen {
    private val status = a.text(size = 13f, color = UiKit.MUTED)
    private val recommendation = a.text(size = 15f)
    private val operators = a.vertical(0)
    private val sites = a.vertical(0)
    private var showAll = false
    private val toggleAll = a.button("Mostra anche le celle deboli/incerte") { showAll = !showAll; render() }

    override val view = a.scroll(a.vertical().apply {
        addView(a.horizontal().apply {
            addView(a.title("Analisi"), UiKit.weight())
            addView(a.button("Ricalcola") { recompute() })
        })
        addView(status)
        addView(a.title("Consiglio"))
        addView(recommendation)
        addView(a.title("Operatori (4G/5G)"))
        addView(operators)
        addView(a.title("Tralicci / celle"))
        addView(sites)
        addView(toggleAll, UiKit.matchWrap())
        addView(a.title("Note per l'impianto"))
        addView(a.text(NOTES, 13f, color = UiKit.MUTED))
    })

    override fun onShown() {
        if (a.report == null) recompute() else render()
    }

    override fun onReport() = render()

    private fun recompute() {
        status.text = "Calcolo in corso…"
        a.runAnalysis { render() }
    }

    private fun render() {
        val r = a.report ?: run {
            status.text = "Nessuna analisi disponibile"
            return
        }
        val home = r.home
        status.text = "${r.totalSamples} punti analizzati · ${r.entities.size} trasmettitori" +
            (if (home == null) " · CASA NON IMPOSTATA" else " · raggio ${r.radiusM.toInt()} m")
        recommendation.text = r.recommendation()

        operators.removeAllViews()
        r.operators.forEachIndexed { i, o ->
            val t = StringBuilder()
            t.append("${i + 1}. ${o.name}   ${o.techs.joinToString("/") { it.label }}\n")
            t.append("Migliore misurato: ${o.bestLevel} dBm")
            if (o.bestNearHome != null) {
                t.append("\nVicino casa: migliore ${o.bestNearHome} dBm (${Quality.rsrpLabel(o.bestNearHome)}), mediana ${o.medianNearHome} dBm su ${o.samplesNearHome} punti")
            } else if (home != null) {
                t.append("\nNessuna misura entro ${r.radiusM.toInt()} m dalla casa")
            }
            t.append("\nBande: ${o.bands.joinToString()}")
            val lvl = o.bestNearHome ?: o.bestLevel
            operators.addView(a.card(Quality.rsrpColor(lvl), a.text(t, 14f)))
        }
        if (r.operators.isEmpty()) operators.addView(a.text("Nessuna misura 4G/5G nel rilievo."))

        sites.removeAllViews()
        val list = r.entities.filter { showAll || isRelevant(it) }
        for (e in list) sites.addView(siteCard(e, home))
        if (list.isEmpty()) sites.addView(a.text("Nessun traliccio stimabile: allarga l'area del rilievo."))
        toggleAll.text = if (showAll) "Mostra solo i principali" else "Mostra anche le celle deboli/incerte (${r.entities.size - r.entities.count { isRelevant(it) }})"
    }

    /** Trasmettitori con abbastanza misure o con posizione nota. */
    private fun isRelevant(e: EntitySummary): Boolean =
        e.known != null || ((e.estimate?.bins ?: 0) >= 8 && (e.tech == Tech.LTE || e.tech == Tech.NR))

    private fun siteCard(e: EntitySummary, home: it.vallequaranta.segnale.analysis.LatLon?): LinearLayout {
        val est = e.estimate
        val t = StringBuilder()
        t.append("${e.operatorName} · ${e.label}")
        if (e.sectors.isNotEmpty()) t.append(" · settori ${e.sectors.joinToString(",")}")
        t.append("\n${e.bands.joinToString()}")
        if (est != null) {
            t.append("\nMisure: ${est.samples} (${est.bins} celle da 10 m) · max ${est.maxLevel} dBm")
            e.bestNearHome?.let { t.append(" · vicino casa $it dBm") }
            est.gradientBearing?.let {
                t.append("\nIl segnale cresce verso ${"%.0f".format(it)}° ${Geo.cardinal(it)}")
                t.append(" (${"%.1f".format(est.gradientDbPer100m)} dB/100 m, R² ${"%.2f".format(est.gradientR2)})")
            }
        }
        if (home != null && e.targetPosition != null) {
            val az = e.bearingFrom(home)!!
            val d = e.distanceFrom(home)!!
            t.append("\n➜ AZIMUT DA CASA ${"%.0f".format(az)}° ${Geo.cardinal(az)} · ${formatDistance(d)}")
            if (e.known != null) {
                t.append(" (posizione nota: ${e.known.label})")
            } else if (est != null) {
                val spread = est.bearingSpread
                t.append(if (spread != null && spread < 90) " ±${"%.0f".format(spread)}°" else " (direzione incerta)")
                t.append(
                    if (est.distanceMax == null) "\nDistanza non determinabile (oltre ${formatDistance(est.distanceMin ?: 0.0)}): allarga il rilievo"
                    else "\nDistanza plausibile ${formatDistance(est.distanceMin ?: 0.0)} – ${formatDistance(est.distanceMax)}"
                )
                if (est.usedTimingAdvance) t.append(" · usato timing advance")
            }
        } else if (home == null) {
            t.append("\nImposta la casa per calcolare l'azimut.")
        }
        val body = a.vertical(0).apply {
            addView(a.text(t, 14f))
            addView(a.horizontal().apply {
                addView(a.button("Punta") {
                    a.target = e
                    a.showScreen(PointScreen::class.java)
                }, UiKit.weight())
                addView(a.button(if (e.known != null) "Modifica posizione" else "Posizione nota…") { editKnown(e) }, UiKit.weight())
            })
        }
        val lvl = e.bestNearHome ?: est?.maxLevel ?: -140
        return a.card(Quality.rsrpColor(lvl), body)
    }

    private fun editKnown(e: EntitySummary) {
        val input = EditText(a).apply {
            hint = "44.12345, 7.12345"
            e.known?.let { setText("%.6f, %.6f".format(java.util.Locale.ROOT, it.pos.lat, it.pos.lon)) }
        }
        val b = AlertDialog.Builder(a)
            .setTitle("Posizione reale del traliccio")
            .setMessage(
                "Se conosci dove si trova il traliccio (visto dal vivo, catasto impianti " +
                    "dell'ARPA regionale, siti di mappatura come cellmapper), inserisci " +
                    "le coordinate: l'azimut diventa esatto."
            )
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val p = SurveyScreen.parseLatLon(input.text.toString())
                if (p == null) a.toast("Formato non valido") else a.runInBackground {
                    a.db.saveKnownSite(KnownSite(e.key, p, "inserita a mano"))
                    a.onMain { a.runAnalysis() }
                }
            }
            .setNegativeButton("Annulla", null)
        if (e.known != null) b.setNeutralButton("Rimuovi") { _, _ ->
            a.runInBackground { a.db.deleteKnownSite(e.key); a.onMain { a.runAnalysis() } }
        }
        b.show()
    }

    companion object {
        const val NOTES =
            "• Azimut riferiti al NORD VERO. La scheda Punta corregge da sola la " +
            "declinazione magnetica; con una bussola da campo aggiungi/togli la " +
            "declinazione locale (in Italia circa +3°/+5°).\n" +
            "• La stima dal gradiente è influenzata da colline ed edifici: se il segnale " +
            "arriva per riflessione può indicare la direzione del riflesso, che però è " +
            "proprio quella da cui l'antenna riceve meglio. Verifica sempre sul tetto " +
            "con la scheda Punta (scansione a 360°).\n" +
            "• Antenna donatrice: direzionale (Yagi o pannello LPDA), in alto, lontana " +
            "almeno 10 m in verticale/orizzontale dall'antenna interna per evitare " +
            "l'autooscillazione. L'amplificatore deve coprire le bande indicate.\n" +
            "• In Italia i ripetitori cellulari sono utilizzabili solo con il consenso " +
            "dell'operatore titolare delle frequenze. Alternativa sempre lecita e spesso " +
            "più efficace: router 4G/5G con antenna esterna direzionale sul tetto + " +
            "chiamate Wi-Fi (VoWiFi) sui telefoni, se supportate dal tuo operatore."
    }
}
