package it.vallequaranta.segnale.ui

import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.widget.FrameLayout
import android.widget.LinearLayout
import it.vallequaranta.segnale.analysis.Geo
import it.vallequaranta.segnale.analysis.LatLon
import it.vallequaranta.segnale.analysis.formatDistance
import it.vallequaranta.segnale.data.Sample
import it.vallequaranta.segnale.radio.Operators
import it.vallequaranta.segnale.radio.Quality
import it.vallequaranta.segnale.ui.UiKit.button
import it.vallequaranta.segnale.ui.UiKit.dp
import it.vallequaranta.segnale.ui.UiKit.horizontal
import it.vallequaranta.segnale.ui.UiKit.text
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File

/**
 * Mappa OpenStreetMap con i punti misurati colorati per livello, la casa,
 * i tralicci stimati e le linee di puntamento casa → traliccio.
 */
class MapScreen(private val a: MainActivity) : Screen {

    /** Filtro dei punti: null = migliore cella 4G/5G di qualunque operatore. */
    private var plmnFilter: String? = null
    private var targetOnly = false
    private var samples: List<Sample> = emptyList()
    private var centered = false

    private val map: MapView
    private val legend = a.text(size = 12f, color = UiKit.TEXT).apply {
        setBackgroundColor(0xDDFFFFFF.toInt())
        setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4))
    }
    private val filterButton = a.button("Filtro: tutti") { chooseFilter() }
    private val points = PointsOverlay()
    private val dynamic = ArrayList<Overlay>()

    override val view: LinearLayout

    init {
        Configuration.getInstance().apply {
            userAgentValue = a.packageName
            osmdroidBasePath = File(a.filesDir, "osmdroid")
            osmdroidTileCache = File(a.cacheDir, "tiles")
        }
        map = MapView(a).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            overlays.add(ScaleBarOverlay(this))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    confirmHome(LatLon(p.latitude, p.longitude))
                    return true
                }
            }))
            overlays.add(points)
        }
        val frame = FrameLayout(a).apply {
            addView(map)
            addView(legend, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
            })
        }
        view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(a.horizontal().apply {
                addView(filterButton, UiKit.weight(2f))
                addView(a.button("Aggiorna") { reload() }, UiKit.weight())
                addView(a.button("Io") { centerOnMe() }, UiKit.weight())
            }, UiKit.matchWrap())
            addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onShown() = reload()
    override fun onReport() = drawSites()
    override fun onLocation() = map.invalidate()
    override fun onResume() = map.onResume()
    override fun onPause() = map.onPause()

    private fun reload() {
        a.runInBackground {
            val s = a.db.samples()
            a.onMain {
                samples = s
                if (!centered) {
                    val c = a.home ?: s.lastOrNull()?.pos ?: a.location?.let { LatLon(it.latitude, it.longitude) }
                    if (c != null) {
                        map.controller.setCenter(GeoPoint(c.lat, c.lon))
                        centered = true
                    }
                }
                drawSites()
            }
        }
        if (a.report == null) a.runAnalysis()
    }

    private fun centerOnMe() {
        val l = a.location ?: return a.toast("Nessun fix GPS")
        map.controller.animateTo(GeoPoint(l.latitude, l.longitude))
    }

    private fun confirmHome(p: LatLon) {
        AlertDialog.Builder(a)
            .setTitle("Impostare qui la casa?")
            .setMessage("%.5f, %.5f".format(p.lat, p.lon))
            .setPositiveButton("Sì") { _, _ -> a.home = p; a.runAnalysis() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun chooseFilter() {
        val plmns = samples.flatMap { s -> s.cells.mapNotNull { it.plmn } }.distinct().sorted()
        val labels = ArrayList<String>()
        labels += "Tutti gli operatori (cella migliore)"
        plmns.forEach { labels += Operators.nameForPlmn(it) }
        a.target?.let { labels += "Solo bersaglio: ${it.operatorName} ${it.label}" }
        AlertDialog.Builder(a)
            .setTitle("Colora i punti per")
            .setItems(labels.toTypedArray()) { _, i ->
                targetOnly = false
                plmnFilter = null
                when {
                    i == 0 -> {}
                    i <= plmns.size -> plmnFilter = plmns[i - 1]
                    else -> targetOnly = true
                }
                filterButton.text = "Filtro: " + labels[i]
                map.invalidate()
                updateLegend()
            }
            .show()
    }

    /** Livello da disegnare per un punto secondo il filtro corrente (null = non disegnare). */
    private fun levelOf(s: Sample): Int? {
        val t = a.target
        val cells = when {
            targetOnly && t != null -> s.cells.filter { it.entityKey == t.key }
            else -> s.cells.filter { plmnFilter == null || it.plmn == plmnFilter }
        }
        if (cells.isEmpty()) return null
        return cells.maxOf { LiveScreen.normalized(it) }
    }

    private fun updateLegend() {
        val drawn = samples.count { levelOf(it) != null }
        legend.text = "$drawn punti · ≥−80 verde · −90 · −100 giallo · −110 arancio · rosso\nPressione lunga: imposta casa"
    }

    private fun drawSites() {
        map.overlays.removeAll(dynamic.toSet())
        dynamic.clear()
        val home = a.home
        if (home != null) {
            dynamic += Marker(map).apply {
                position = GeoPoint(home.lat, home.lon)
                title = "Casa"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
        }
        val r = a.report
        if (r != null) {
            for (e in r.entities) {
                val p = e.targetPosition ?: continue
                // Si mostrano solo stime con un minimo di affidabilità
                val est = e.estimate
                if (e.known == null && (est == null || est.bins < 8)) continue
                val color = if (e.known != null) Color.rgb(0, 120, 0) else if (est?.isWellConstrained == true) Color.BLUE else Color.GRAY
                val snippet = StringBuilder("${e.tech.label} ${e.bands.joinToString()}")
                if (home != null) {
                    val az = Geo.bearing(home, p)
                    snippet.append("<br>Azimut ${"%.0f".format(az)}° ${Geo.cardinal(az)} · ${formatDistance(Geo.distance(home, p))}")
                }
                snippet.append(if (e.known != null) "<br>Posizione nota" else "<br>Stimata" + (est?.bearingSpread?.let { " ±%.0f°".format(it) } ?: ""))
                dynamic += Marker(map).apply {
                    position = GeoPoint(p.lat, p.lon)
                    title = "${e.operatorName} ${e.label}"
                    this.snippet = snippet.toString()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = icon?.mutate()?.also { it.setTint(color) }
                }
                if (home != null) {
                    dynamic += Polyline(map).apply {
                        setPoints(listOf(GeoPoint(home.lat, home.lon), GeoPoint(p.lat, p.lon)))
                        outlinePaint.color = color
                        outlinePaint.strokeWidth = a.dp(2).toFloat()
                        outlinePaint.alpha = 160
                    }
                }
            }
        }
        map.overlays.addAll(dynamic)
        updateLegend()
        map.invalidate()
    }

    /** Disegna migliaia di punti in modo leggero (niente Marker per punto). */
    private inner class PointsOverlay : Overlay() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.DKGRAY
            strokeWidth = 1f
        }
        private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 150, 243) }
        private val pt = Point()

        override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
            if (shadow) return
            val pj = osmv.projection
            val radius = a.dp(5).toFloat()
            for (s in samples) {
                val lvl = levelOf(s) ?: continue
                pj.toPixels(GeoPoint(s.pos.lat, s.pos.lon), pt)
                fill.color = Quality.rsrpColor(lvl)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, fill)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, stroke)
            }
            a.location?.let {
                pj.toPixels(GeoPoint(it.latitude, it.longitude), pt)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius * 1.6f, me)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius * 1.6f, stroke)
            }
        }
    }
}
