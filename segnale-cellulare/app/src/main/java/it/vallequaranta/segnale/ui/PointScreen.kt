package it.vallequaranta.segnale.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import it.vallequaranta.segnale.analysis.Geo
import it.vallequaranta.segnale.analysis.formatDistance
import it.vallequaranta.segnale.radio.CellMeasurement
import it.vallequaranta.segnale.radio.Quality
import it.vallequaranta.segnale.radio.Tech
import it.vallequaranta.segnale.ui.UiKit.button
import it.vallequaranta.segnale.ui.UiKit.dp
import it.vallequaranta.segnale.ui.UiKit.horizontal
import it.vallequaranta.segnale.ui.UiKit.scroll
import it.vallequaranta.segnale.ui.UiKit.text
import it.vallequaranta.segnale.ui.UiKit.vertical
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Puntamento dell'antenna sul posto:
 * - bussola con freccia verso il traliccio scelto (nord vero);
 * - livello in tempo reale della cella bersaglio, con segnale acustico;
 * - scansione a 360°: ruotando lentamente su se stessi, il corpo scherma il
 *   telefono da un lato e il livello misurato indica la direzione migliore.
 */
class PointScreen(private val a: MainActivity) : Screen {
    private val targetText = a.text(size = 14f)
    private val levelText = a.text(size = 34f, bold = true)
    private val infoText = a.text(size = 15f)
    private val compassView = CompassView(a)
    private val scanButton = a.button("Scansione 360°") { toggleScan() }
    private val beepButton = a.button("Suono: spento") { toggleBeep() }
    private val scanResult = a.text(size = 14f)

    private var scanning = false
    private val scan = ArrayList<Pair<Double, Int>>()
    private var lastLevel: Int? = null
    private var beepOn = false
    private var tone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())

    override val view = a.scroll(a.vertical().apply {
        addView(targetText)
        addView(a.horizontal().apply {
            addView(levelText, UiKit.weight())
            addView(beepButton)
        })
        addView(infoText)
        addView(compassView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(320)))
        addView(a.horizontal().apply {
            addView(scanButton, UiKit.weight())
            addView(a.button("Azzera") { scan.clear(); scanResult.text = ""; compassView.invalidate() }, UiKit.weight())
        })
        addView(scanResult)
        addView(a.text(HELP, 12f, color = UiKit.MUTED))
    })

    /** Celle da seguire: quelle del bersaglio o, in mancanza, la cella servente. */
    private fun tracked(): CellMeasurement? {
        val t = a.target
        return if (t != null) a.cells.filter { it.entityKey == t.key }.maxByOrNull { it.level }
        else a.cells.firstOrNull { it.registered && (it.tech == Tech.LTE || it.tech == Tech.NR) } ?: a.cells.firstOrNull { it.registered }
    }

    private fun targetBearing(): Double? {
        val ref = a.referencePosition() ?: return null
        return a.target?.bearingFrom(ref)
    }

    override fun onShown() = render()
    override fun onHeading() {
        compassView.invalidate()
        renderInfo()
    }

    override fun onCells() {
        val c = tracked()
        val h = a.heading
        if (scanning && c != null && h != null) {
            scan += h to c.level
            updateScanResult()
        }
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(beeper)
        tone?.release()
        tone = null
    }

    override fun onResume() {
        if (beepOn) handler.post(beeper)
    }

    private fun render() {
        val t = a.target
        targetText.text = if (t == null) {
            "Nessun bersaglio scelto (tasto \"Punta\" nella scheda Analisi): segue la cella servente."
        } else {
            val ref = a.referencePosition()
            val pos = t.targetPosition
            "Bersaglio: ${t.operatorName} ${t.label} · ${t.bands.joinToString()}" +
                if (ref != null && pos != null) "\nDistanza ${formatDistance(Geo.distance(ref, pos))}" +
                    (if (t.known == null) " (stimata)" else "") else ""
        }
        val c = tracked()
        if (c == null) {
            levelText.text = "— dBm"
            levelText.setTextColor(UiKit.MUTED)
        } else {
            val trend = lastLevel?.let { c.level - it }?.let { if (it > 0) " ▲$it" else if (it < 0) " ▼${-it}" else "" } ?: ""
            levelText.text = "${c.level} dBm$trend"
            levelText.setTextColor(Quality.rsrpColor(LiveScreen.normalized(c)))
            lastLevel = c.level
        }
        renderInfo()
        compassView.invalidate()
    }

    private fun renderInfo() {
        val h = a.heading
        val tb = targetBearing()
        val c = tracked()
        val sb = StringBuilder()
        if (!a.compassAvailable()) sb.append("Bussola non disponibile su questo telefono.\n")
        if (h != null) sb.append("Direzione telefono ${h.roundToInt()}° ${Geo.cardinal(h)}")
        if (tb != null) {
            sb.append(" · bersaglio ${tb.roundToInt()}° ${Geo.cardinal(tb)}")
            if (h != null) {
                val d = Geo.angleDiff(tb, h)
                sb.append(
                    when {
                        abs(d) <= 5 -> "\n✔ ALLINEATO"
                        d > 0 -> "\nRuota di ${d.roundToInt()}° a destra"
                        else -> "\nRuota di ${(-d).roundToInt()}° a sinistra"
                    }
                )
            }
        }
        if (c != null) {
            c.quality?.let { sb.append("\nRSRQ $it dB") }
            c.sinr?.let { sb.append(" · SINR $it dB") }
        }
        sb.append("\nDeclinazione applicata ${"%+.1f".format(a.declination())}°")
        if (a.headingAccuracy < 2) sb.append(" · bussola poco precisa: muovi il telefono a \"8\"")
        infoText.text = sb.toString()
    }

    private fun toggleScan() {
        scanning = !scanning
        if (scanning) scan.clear()
        scanButton.text = if (scanning) "Ferma scansione" else "Scansione 360°"
        if (!scanning) updateScanResult()
    }

    /** Direzione con livello medio massimo (settori di 10°, media mobile su 30°). */
    fun bestDirection(): Pair<Double, Double>? {
        if (scan.size < 6) return null
        val sum = DoubleArray(36)
        val n = IntArray(36)
        for ((h, l) in scan) {
            val b = (Geo.normalize(h) / 10).toInt() % 36
            sum[b] += l.toDouble(); n[b]++
        }
        var best = -1
        var bestVal = Double.NEGATIVE_INFINITY
        for (b in 0 until 36) {
            var s = 0.0; var k = 0
            for (o in -1..1) {
                val j = (b + o + 36) % 36
                if (n[j] > 0) { s += sum[j] / n[j]; k++ }
            }
            if (k >= 2 && s / k > bestVal) { bestVal = s / k; best = b }
        }
        return if (best < 0) null else (best * 10 + 5.0) to bestVal
    }

    private fun updateScanResult() {
        val covered = scan.map { (Geo.normalize(it.first) / 10).toInt() }.distinct().size * 10
        val best = bestDirection()
        val minL = scan.minOfOrNull { it.second }
        val maxL = scan.maxOfOrNull { it.second }
        scanResult.text = "Scansione: ${scan.size} misure, copertura $covered°" +
            (if (best != null) "\nMassimo verso ${best.first.roundToInt()}° ${Geo.cardinal(best.first)} (media ${"%.0f".format(best.second)} dBm, escursione ${minL}…${maxL})" else "") +
            (if (covered < 300) "\nContinua a ruotare lentamente (circa un giro al minuto)." else "")
        compassView.invalidate()
    }

    private fun toggleBeep() {
        beepOn = !beepOn
        beepButton.text = if (beepOn) "Suono: acceso" else "Suono: spento"
        handler.removeCallbacks(beeper)
        if (beepOn) handler.post(beeper)
    }

    /** Beep sempre più frequenti al crescere del segnale: utile sul tetto. */
    private val beeper = object : Runnable {
        override fun run() {
            if (!beepOn) return
            val lvl = tracked()?.let { LiveScreen.normalized(it) }
            if (lvl != null) {
                val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 80).also { tone = it }
                t.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            }
            val interval = if (lvl == null) 1500L else (1500 - (lvl + 125) * 27).toLong().coerceIn(120L, 1500L)
            handler.postDelayed(this, interval)
        }
    }

    /** Rosa dei venti ruotata secondo la direzione del telefono + diagramma polare della scansione. */
    private inner class CompassView(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); color = Color.DKGRAY }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dp(1).toFloat(); color = Color.GRAY }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dp(14).toFloat(); textAlign = Paint.Align.CENTER; color = Color.BLACK }
        private val north = Paint(label).apply { color = Color.RED; isFakeBoldText = true }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiKit.PRIMARY; style = Paint.Style.FILL }
        private val phone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = ctx.dp(3).toFloat() }
        private val polar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5543A047; style = Paint.Style.FILL }
        private val polarEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7D32.toInt(); style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(cx, cy) - dp(20)
            val h = (a.heading ?: 0.0).toFloat()

            // In alto c'è sempre la direzione in cui punta il telefono
            c.drawLine(cx, cy - r - dp(14), cx, cy - r + dp(10), phone)

            c.save()
            c.rotate(-h, cx, cy)
            c.drawCircle(cx, cy, r, ring)
            for (deg in 0 until 360 step 10) {
                val len = if (deg % 30 == 0) dp(12) else dp(6)
                c.save(); c.rotate(deg.toFloat(), cx, cy)
                c.drawLine(cx, cy - r, cx, cy - r + len, tick)
                c.restore()
            }
            val names = listOf("N", "E", "S", "O")
            for (i in 0 until 4) {
                c.save(); c.rotate(i * 90f, cx, cy)
                c.drawText(names[i], cx, cy - r + dp(28), if (i == 0) north else label)
                c.restore()
            }
            drawPolar(c, cx, cy, r * 0.8f)
            targetBearing()?.let { tb ->
                c.save(); c.rotate(tb.toFloat(), cx, cy)
                val p = Path().apply {
                    moveTo(cx, cy - r * 0.95f)
                    lineTo(cx - dp(14), cy - r * 0.55f)
                    lineTo(cx - dp(4), cy - r * 0.55f)
                    lineTo(cx - dp(4), cy)
                    lineTo(cx + dp(4), cy)
                    lineTo(cx + dp(4), cy - r * 0.55f)
                    lineTo(cx + dp(14), cy - r * 0.55f)
                    close()
                }
                c.drawPath(p, arrow)
                c.restore()
            }
            c.restore()
        }

        /** Livello in funzione della direzione: raggio proporzionale ai dB sopra il minimo. */
        private fun drawPolar(c: Canvas, cx: Float, cy: Float, r: Float) {
            if (scan.size < 3) return
            val sum = DoubleArray(36)
            val n = IntArray(36)
            for ((h, l) in scan) {
                val b = (Geo.normalize(h) / 10).toInt() % 36
                sum[b] += l.toDouble(); n[b]++
            }
            val avg = (0 until 36).map { if (n[it] > 0) sum[it] / n[it] else null }
            val present = avg.filterNotNull()
            val lo = present.min() - 3
            val hi = present.max()
            val span = (hi - lo).coerceAtLeast(6.0)
            val path = Path()
            var started = false
            for (b in 0 until 36) {
                val v = avg[b] ?: continue
                val rr = r * ((v - lo) / span).toFloat()
                val ang = Math.toRadians(b * 10 + 5.0)
                val x = cx + rr * sin(ang).toFloat()
                val y = cy - rr * cos(ang).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, polar)
            c.drawPath(path, polarEdge)
            bestDirection()?.let { (dir, _) ->
                val ang = Math.toRadians(dir)
                c.drawLine(cx, cy, cx + r * sin(ang).toFloat(), cy - r * cos(ang).toFloat(), polarEdge)
            }
        }

        private fun dp(v: Int) = context.dp(v).toFloat()
    }

    companion object {
        const val HELP =
            "Sul tetto, nel punto dove monterai l'antenna: tieni il telefono in verticale " +
            "davanti a te (la fotocamera guarda dove punti) e ruota finché la freccia blu " +
            "è in alto. Poi fai una scansione a 360° ruotando lentamente su te stesso: il " +
            "tuo corpo scherma il segnale alle spalle, quindi il massimo del diagramma " +
            "verde indica da dove arriva il segnale (±30° circa). Tieni lontano il " +
            "telefono da ringhiere e lamiere, che disturbano la bussola.\n" +
            "Con l'antenna montata: collega il cavo al router/amplificatore, ruota l'antenna " +
            "di 5° alla volta attendendo ~10 s e fissala dove RSRP e SINR sono massimi."
    }
}
