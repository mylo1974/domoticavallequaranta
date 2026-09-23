package it.vallequaranta.segnale.ui

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import it.vallequaranta.segnale.analysis.LatLon
import it.vallequaranta.segnale.data.Exporter
import it.vallequaranta.segnale.ui.UiKit.button
import it.vallequaranta.segnale.ui.UiKit.horizontal
import it.vallequaranta.segnale.ui.UiKit.scroll
import it.vallequaranta.segnale.ui.UiKit.text
import it.vallequaranta.segnale.ui.UiKit.title
import it.vallequaranta.segnale.ui.UiKit.vertical

/** Avvio/arresto del rilievo, posizione della casa, parametri ed esportazione. */
class SurveyScreen(private val a: MainActivity) : Screen {
    private val status = a.text(size = 16f, bold = true)
    private val details = a.text(size = 13f, color = UiKit.MUTED)
    private val homeText = a.text(size = 14f)
    private val startStop = a.button("Avvia rilievo") { toggle() }

    override val view = a.scroll(a.vertical().apply {
        addView(a.title("Rilievo"))
        addView(status)
        addView(details)
        addView(startStop, UiKit.matchWrap())

        addView(a.title("Casa"))
        addView(homeText)
        addView(a.horizontal().apply {
            addView(a.button("Casa = posizione GPS") { setHomeFromGps() }, UiKit.weight())
            addView(a.button("Inserisci coordinate") { askHomeCoordinates() }, UiKit.weight())
        })
        addView(a.text("Puoi anche tenere premuto sulla mappa per impostare la casa.", 12f, color = UiKit.MUTED))

        addView(a.title("Parametri"))
        addView(a.horizontal().apply {
            addView(a.button("Raggio confronto") { askNumber("Raggio attorno alla casa (m)", a.radiusM) { a.radiusM = it; render() } }, UiKit.weight())
            addView(a.button("Precisione GPS") { askNumber("Accuratezza GPS massima (m)", a.maxAccuracyM.toDouble()) { a.maxAccuracyM = it.toFloat(); render() } }, UiKit.weight())
        })

        addView(a.title("Dati"))
        addView(a.horizontal().apply {
            addView(a.button("Esporta CSV") { exportCsv() }, UiKit.weight())
            addView(a.button("Esporta KML") { exportKml() }, UiKit.weight())
        })
        addView(a.button("Cancella tutti i rilievi") { confirmClear() }, UiKit.matchWrap())

        addView(a.title("Come fare il rilievo"))
        addView(a.text(GUIDE, 14f))
    })

    override fun onShown() = render()
    override fun onCells() = render()
    override fun onLocation() = render()

    private fun toggle() {
        a.setRecording(!a.recording)
        if (!a.recording) a.runAnalysis()
        render()
    }

    private fun render() {
        startStop.text = if (a.recording) "Ferma rilievo" else "Avvia rilievo"
        val l = a.location
        status.text = if (a.recording) "● REGISTRAZIONE: ${a.recordedPoints} punti" else "Rilievo fermo"
        status.setTextColor(if (a.recording) 0xFFD32F2F.toInt() else UiKit.TEXT)
        val gps = when {
            !a.gpsEnabled() -> "GPS spento"
            l == null -> "GPS in attesa del fix"
            l.accuracy > a.maxAccuracyM -> "GPS ±${l.accuracy.toInt()} m: troppo impreciso, punti scartati"
            else -> "GPS ±${l.accuracy.toInt()} m OK"
        }
        a.runInBackground {
            val total = a.db.sampleCount()
            a.onMain {
                details.text = "$gps\nPunti in archivio: $total" +
                    (if (a.recording && a.skippedPoints > 0) " · scartati per GPS: ${a.skippedPoints}" else "") +
                    "\nRaggio confronto: ${a.radiusM.toInt()} m · precisione max: ${a.maxAccuracyM.toInt()} m"
            }
        }
        val h = a.home
        homeText.text = if (h == null) "Casa non impostata" else "Casa: %.5f, %.5f".format(h.lat, h.lon)
    }

    private fun setHomeFromGps() {
        val l = a.location
        if (l == null || l.accuracy > 30) {
            a.toast("Attendi un fix GPS preciso (sotto 30 m), meglio all'aperto.")
            return
        }
        a.home = LatLon(l.latitude, l.longitude)
        a.toast("Casa impostata (±${l.accuracy.toInt()} m)")
        a.runAnalysis()
        render()
    }

    private fun askHomeCoordinates() {
        val input = EditText(a).apply {
            hint = "44.12345, 7.12345"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(a)
            .setTitle("Coordinate casa (lat, lon)")
            .setMessage("Puoi copiarle da Google Maps tenendo premuto sul tetto della casa.")
            .setView(input)
            .setPositiveButton("Salva") { _, _ ->
                val p = parseLatLon(input.text.toString())
                if (p == null) a.toast("Formato non valido") else {
                    a.home = p; a.runAnalysis(); render()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun askNumber(title: String, current: Double, onValue: (Double) -> Unit) {
        val input = EditText(a).apply {
            setText(current.toInt().toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(a).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue)
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun exportCsv() {
        a.runInBackground {
            val msg = try {
                val name = Exporter.saveToDownloads(a, "rilievo", "csv", "text/csv", Exporter.csv(a.db.samples()))
                "Salvato in $name"
            } catch (e: Exception) {
                "Esportazione fallita: ${e.message}"
            }
            a.onMain { a.toast(msg) }
        }
    }

    private fun exportKml() {
        val r = a.report
        if (r == null) {
            a.toast("Esegui prima l'analisi (scheda Analisi).")
            return
        }
        a.runInBackground {
            val msg = try {
                "Salvato in " + Exporter.saveToDownloads(a, "tralicci", "kml", "application/vnd.google-earth.kml+xml", Exporter.kml(r))
            } catch (e: Exception) {
                "Esportazione fallita: ${e.message}"
            }
            a.onMain { a.toast(msg) }
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(a)
            .setTitle("Cancellare tutti i rilievi?")
            .setMessage("Le posizioni note dei tralicci inserite a mano vengono conservate.")
            .setPositiveButton("Cancella") { _, _ ->
                a.runInBackground {
                    a.db.clear()
                    a.onMain { a.runAnalysis(); render() }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    companion object {
        fun parseLatLon(s: String): LatLon? {
            val parts = s.trim().split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return LatLon(lat, lon)
        }

        const val GUIDE =
            "1. Imposta la casa (dal tetto o dal giardino, con GPS preciso).\n" +
            "2. Premi Avvia e cammina lentamente: attorno alla casa, poi lungo le strade " +
            "vicine fino ai punti dove il telefono prende bene e oltre (200-500 m). Più " +
            "l'area è ampia e in direzioni diverse, più la stima dei tralicci è precisa.\n" +
            "3. Tieni il telefono sempre nello stesso modo (in mano, davanti a te). Evita " +
            "di stare in auto: la carrozzeria attenua 5-10 dB.\n" +
            "4. Se hai un doppia SIM, metti due operatori diversi: vengono misurati insieme. " +
            "Per gli altri ripeti il giro con SIM diverse (bastano prepagate).\n" +
            "5. Nella scheda Analisi trovi classifica operatori e azimut dei tralicci; nella " +
            "scheda Punta la bussola per orientare l'antenna sul tetto.\n\n" +
            "Nota: i rilievi contengono la posizione della casa e restano solo sul telefono."
    }
}
