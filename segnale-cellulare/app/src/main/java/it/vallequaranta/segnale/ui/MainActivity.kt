package it.vallequaranta.segnale.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import it.vallequaranta.segnale.analysis.Analyzer
import it.vallequaranta.segnale.analysis.EntitySummary
import it.vallequaranta.segnale.analysis.LatLon
import it.vallequaranta.segnale.analysis.Report
import it.vallequaranta.segnale.data.Sample
import it.vallequaranta.segnale.data.SurveyDb
import it.vallequaranta.segnale.radio.CellMeasurement
import it.vallequaranta.segnale.radio.CellScanner
import it.vallequaranta.segnale.sensors.Compass
import it.vallequaranta.segnale.sensors.GpsTracker
import it.vallequaranta.segnale.ui.UiKit.dp
import java.util.concurrent.Executors

/**
 * Activity unica: contiene lo stato condiviso (celle, posizione, bussola,
 * rilievo in corso, risultati dell'analisi) e cinque schermate a schede.
 */
class MainActivity : Activity() {

    // --- stato condiviso fra le schermate ---
    var cells: List<CellMeasurement> = emptyList()
        private set
    var location: Location? = null
        private set
    var heading: Double? = null
        private set
    var headingAccuracy = 0
        private set
    var recording = false
        private set
    var report: Report? = null
        private set
    var target: EntitySummary? = null
    var lastScanTime = 0L
        private set

    lateinit var db: SurveyDb
        private set
    private val prefs by lazy { getSharedPreferences("impostazioni", Context.MODE_PRIVATE) }

    var home: LatLon?
        get() = if (prefs.contains("home_lat")) LatLon(
            prefs.getString("home_lat", "0")!!.toDouble(), prefs.getString("home_lon", "0")!!.toDouble()
        ) else null
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("home_lat").remove("home_lon")
                else putString("home_lat", v.lat.toString()).putString("home_lon", v.lon.toString())
            }.apply()
        }

    /** Raggio attorno alla casa entro cui confrontare gli operatori. */
    var radiusM: Double
        get() = prefs.getFloat("radius", 100f).toDouble()
        set(v) = prefs.edit().putFloat("radius", v.toFloat()).apply()

    /** Accuratezza GPS massima accettata per registrare un punto. */
    var maxAccuracyM: Float
        get() = prefs.getFloat("max_acc", 20f)
        set(v) = prefs.edit().putFloat("max_acc", v).apply()

    // --- infrastruttura ---
    private val main = Handler(Looper.getMainLooper())
    private val radioThread = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var scanner: CellScanner
    private lateinit var gps: GpsTracker
    private lateinit var compass: Compass
    private var running = false

    private lateinit var content: FrameLayout
    private lateinit var screens: List<Pair<String, Screen>>
    private val tabButtons = ArrayList<Button>()
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        db = SurveyDb(this)
        scanner = CellScanner(this, radioThread)
        gps = GpsTracker(this) { onLocation(it) }
        compass = Compass(this) { h, acc ->
            heading = h
            headingAccuracy = acc
            screens[current].second.onHeading()
        }

        screens = listOf(
            "Live" to LiveScreen(this),
            "Rilievo" to SurveyScreen(this),
            "Mappa" to MapScreen(this),
            "Analisi" to AnalysisScreen(this),
            "Punta" to PointScreen(this),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        screens.forEachIndexed { i, (label, _) ->
            val b = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                setOnClickListener { show(i) }
            }
            tabButtons += b
            bar.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        show(0)
        requestPermissionsIfNeeded()
    }

    fun show(i: Int) {
        current = i
        content.removeAllViews()
        content.addView(screens[i].second.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabButtons.forEachIndexed { k, b -> b.setTextColor(if (k == i) UiKit.PRIMARY else UiKit.MUTED) }
        screens[i].second.onShown()
    }

    fun showScreen(type: Class<out Screen>) {
        val i = screens.indexOfFirst { type.isInstance(it.second) }
        if (i >= 0) show(i)
    }

    // --- permessi ---
    private val needed = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
    )

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasLocationPermission()) {
            AlertDialog.Builder(this)
                .setTitle("Permesso posizione necessario")
                .setMessage("Android fornisce i dati delle celle radio solo alle app con accesso alla posizione precisa. Concedilo dalle impostazioni dell'app.")
                .setPositiveButton("OK", null)
                .show()
        } else if (running) {
            gps.start()
        }
    }

    // --- ciclo di vita ---
    override fun onResume() {
        super.onResume()
        running = true
        if (hasLocationPermission()) gps.start()
        compass.start()
        main.post(scanLoop)
        screens.forEach { it.second.onResume() }
    }

    override fun onPause() {
        super.onPause()
        running = false
        main.removeCallbacks(scanLoop)
        gps.stop()
        compass.stop()
        screens.forEach { it.second.onPause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        radioThread.shutdown()
        worker.shutdown()
        db.close()
    }

    /** Scansione radio ogni ~2 s (il modem raramente aggiorna più spesso). */
    private val scanLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (hasLocationPermission()) {
                scanner.scan { list -> main.post { onCells(list) } }
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun onCells(list: List<CellMeasurement>) {
        cells = list
        lastScanTime = System.currentTimeMillis()
        if (recording) maybeRecord(list)
        screens[current].second.onCells()
    }

    private fun onLocation(l: Location) {
        location = l
        compass.updateLocation(LatLon(l.latitude, l.longitude), if (l.hasAltitude()) l.altitude else null)
        screens[current].second.onLocation()
    }

    // --- rilievo ---
    var recordedPoints = 0
        private set
    var skippedPoints = 0
        private set

    fun setRecording(on: Boolean) {
        recording = on
        if (on) { recordedPoints = 0; skippedPoints = 0 }
    }

    /** Registra un punto solo se il GPS è recente e preciso. */
    private fun maybeRecord(list: List<CellMeasurement>) {
        val l = location
        val fresh = l != null && System.currentTimeMillis() - l.time < 5000
        if (l == null || !fresh || !l.hasAccuracy() || l.accuracy > maxAccuracyM || list.isEmpty()) {
            skippedPoints++
            return
        }
        val s = Sample(
            time = System.currentTimeMillis(),
            pos = LatLon(l.latitude, l.longitude),
            accuracy = l.accuracy,
            altitude = if (l.hasAltitude()) l.altitude else null,
            cells = list,
        )
        worker.execute { db.insert(s) }
        recordedPoints++
    }

    // --- analisi ---
    fun runAnalysis(onDone: (() -> Unit)? = null) {
        val h = home
        val r = radiusM
        worker.execute {
            val result = try {
                Analyzer.analyze(db.records(), h, r, db.knownSites())
            } catch (e: Exception) {
                main.post { toast("Errore nell'analisi: ${e.message}") }
                null
            }
            main.post {
                if (result != null) {
                    report = result
                    // Aggiorna il bersaglio con i dati nuovi
                    target = target?.let { t -> result.entities.firstOrNull { it.key == t.key } }
                    screens.forEach { it.second.onReport() }
                }
                onDone?.invoke()
            }
        }
    }

    fun runInBackground(task: () -> Unit) = worker.execute(task)
    fun onMain(task: () -> Unit) = main.post(task)

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Posizione di riferimento: GPS attuale se preciso, altrimenti la casa. */
    fun referencePosition(): LatLon? {
        val l = location
        if (l != null && l.accuracy <= 30 && System.currentTimeMillis() - l.time < 10000) return LatLon(l.latitude, l.longitude)
        return home
    }

    fun gpsEnabled() = gps.enabled
    fun compassAvailable() = compass.available
    fun declination() = compass.declination
}
