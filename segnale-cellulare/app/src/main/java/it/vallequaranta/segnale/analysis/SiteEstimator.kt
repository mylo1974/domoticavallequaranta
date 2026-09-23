package it.vallequaranta.segnale.analysis

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Una misura georeferenziata di un trasmettitore. */
data class Obs(val pos: LatLon, val level: Int, val taMeters: Double? = null)

/**
 * Risultato della stima per un trasmettitore (sito/cella).
 *
 * @property gradientBearing direzione (nord vero) in cui il segnale cresce più
 *   rapidamente nell'area rilevata: indica da dove arriva il segnale.
 * @property location posizione più probabile del traliccio
 * @property bearingFromRef azimut dal punto di riferimento (casa) alla posizione stimata
 * @property bearingSpread semi-ampiezza (°) del cono che contiene la regione di confidenza
 * @property distanceMin/distanceMax intervallo di distanza plausibile; distanceMax null = non limitata
 */
data class SiteEstimate(
    val samples: Int,
    val bins: Int,
    val maxLevel: Int,
    val bestPoint: LatLon,
    val gradientBearing: Double?,
    val gradientDbPer100m: Double?,
    val gradientR2: Double?,
    val location: LatLon?,
    val pathLossExponent: Double?,
    val bearingFromRef: Double?,
    val bearingSpread: Double?,
    val distanceFromRef: Double?,
    val distanceMin: Double?,
    val distanceMax: Double?,
    val usedTimingAdvance: Boolean,
) {
    /** Stima affidabile: cono stretto e distanza limitata. */
    val isWellConstrained: Boolean
        get() = bearingSpread != null && bearingSpread <= 25.0 && distanceMax != null
}

/**
 * Stima la posizione del traliccio dalle misure raccolte camminando.
 *
 * Metodo:
 * 1. le misure vengono aggregate in celle di 10 m (media), per non dare peso
 *    eccessivo ai punti dove ci si è fermati;
 * 2. regressione lineare livello ~ est + nord → gradiente, cioè la direzione
 *    di provenienza del segnale nell'area rilevata (robusta anche con aree piccole);
 * 3. modello di propagazione log-distanza  L = P0 − 10·n·log10(d), con n in 2…4,
 *    adattato con ricerca a griglia (coarse → fine) sulla posizione del traliccio;
 *    il timing advance, quando presente, vincola la distanza;
 * 4. la regione di confidenza (Δχ² ≤ 6, ~95% per 2 parametri) dà l'incertezza
 *    su azimut e distanza visti dalla casa.
 *
 * Attenzione: colline e edifici (shadowing) deviano il gradiente. Più l'area
 * rilevata è ampia e varia (strade attorno alla casa, punti a 200-500 m),
 * più la stima è affidabile.
 */
object SiteEstimator {
    private const val BIN_M = 10.0
    private const val SIGMA_DB = 6.0        // shadowing tipico
    private const val SIGMA_TA_M = 120.0    // risoluzione TA LTE + multipath
    private const val MAX_EFFECTIVE_N = 30.0 // le misure vicine sono correlate
    private const val MIN_DIST_M = 30.0
    private const val DELTA_CHI2 = 6.0
    private val exponents = doubleArrayOf(2.0, 2.5, 3.0, 3.5, 4.0)

    private class Bin(val x: Double, val y: Double, val level: Double, val weight: Double, val ta: Double?)

    fun estimate(obs: List<Obs>, reference: LatLon?): SiteEstimate? {
        if (obs.isEmpty()) return null
        val proj = LocalProjection(reference ?: obs.first().pos)
        val bins = binning(obs, proj)
        val best = bins.maxBy { it.level }
        val bestPoint = proj.toLatLon(best.x, best.y)
        val maxLevel = obs.maxOf { it.level }

        val grad = gradient(bins)
        val fit = if (bins.size >= 4) fitLocation(bins) else null

        var bearingFromRef: Double? = null
        var spread: Double? = null
        var dist: Double? = null
        var dMin: Double? = null
        var dMax: Double? = null
        var location: LatLon? = null
        if (fit != null) {
            location = proj.toLatLon(fit.x, fit.y)
            // Il riferimento è l'origine della proiezione (casa, oppure primo punto)
            bearingFromRef = compass(fit.x, fit.y)
            dist = hypot(fit.x, fit.y)
            var lo = 0.0
            var hi = 0.0
            var rMin = Double.MAX_VALUE
            var rMax = 0.0
            for (p in fit.region) {
                val r = hypot(p[0], p[1])
                rMin = min(rMin, r)
                rMax = max(rMax, r)
                if (r < 1.0) {
                    // la regione contiene la casa stessa: direzione indeterminata
                    lo = -180.0; hi = 180.0
                    continue
                }
                val d = Geo.angleDiff(compass(p[0], p[1]), bearingFromRef)
                lo = min(lo, d)
                hi = max(hi, d)
            }
            spread = max(abs(lo), abs(hi))
            dMin = rMin
            dMax = if (fit.touchesEdge) null else rMax
        }

        return SiteEstimate(
            samples = obs.size,
            bins = bins.size,
            maxLevel = maxLevel,
            bestPoint = bestPoint,
            gradientBearing = grad?.first,
            gradientDbPer100m = grad?.second,
            gradientR2 = grad?.third,
            location = location,
            pathLossExponent = fit?.n,
            bearingFromRef = bearingFromRef,
            bearingSpread = spread,
            distanceFromRef = dist,
            distanceMin = dMin,
            distanceMax = dMax,
            usedTimingAdvance = bins.any { it.ta != null },
        )
    }

    /** Azimut bussola di un vettore (est, nord). */
    private fun compass(x: Double, y: Double) = Geo.normalize(Math.toDegrees(atan2(x, y)))

    private fun binning(obs: List<Obs>, proj: LocalProjection): List<Bin> {
        class Acc { var sx = 0.0; var sy = 0.0; var sl = 0.0; var n = 0; var sta = 0.0; var nta = 0 }
        val map = HashMap<Long, Acc>()
        for (o in obs) {
            val xy = proj.toXY(o.pos)
            val key = (floor(xy[0] / BIN_M).toLong() shl 32) xor (floor(xy[1] / BIN_M).toLong() and 0xFFFFFFFFL)
            val a = map.getOrPut(key) { Acc() }
            a.sx += xy[0]; a.sy += xy[1]; a.sl += o.level; a.n++
            if (o.taMeters != null) { a.sta += o.taMeters; a.nta++ }
        }
        return map.values.map {
            Bin(it.sx / it.n, it.sy / it.n, it.sl / it.n, sqrt(it.n.toDouble()), if (it.nta > 0) it.sta / it.nta else null)
        }
    }

    /** Regressione pesata level = a + bx·x + by·y. Restituisce (azimut, dB/100m, R²). */
    private fun gradient(bins: List<Bin>): Triple<Double, Double, Double>? {
        if (bins.size < 3) return null
        var sw = 0.0; var mx = 0.0; var my = 0.0; var ml = 0.0
        for (b in bins) { sw += b.weight; mx += b.weight * b.x; my += b.weight * b.y; ml += b.weight * b.level }
        mx /= sw; my /= sw; ml /= sw
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sxl = 0.0; var syl = 0.0; var sll = 0.0
        for (b in bins) {
            val dx = b.x - mx; val dy = b.y - my; val dl = b.level - ml
            sxx += b.weight * dx * dx; syy += b.weight * dy * dy; sxy += b.weight * dx * dy
            sxl += b.weight * dx * dl; syl += b.weight * dy * dl; sll += b.weight * dl * dl
        }
        val det = sxx * syy - sxy * sxy
        // Area rilevata troppo piccola o allineata su una retta
        if (det <= 1e-6 || sxx / sw < 100.0 || syy / sw < 100.0) return null
        val bx = (sxl * syy - syl * sxy) / det
        val by = (syl * sxx - sxl * sxy) / det
        val explained = bx * sxl + by * syl
        val r2 = if (sll > 0) (explained / sll).coerceIn(0.0, 1.0) else 0.0
        return Triple(compass(bx, by), hypot(bx, by) * 100.0, r2)
    }

    private class Fit(val x: Double, val y: Double, val n: Double, val region: List<DoubleArray>, val touchesEdge: Boolean)

    private fun fitLocation(bins: List<Bin>): Fit {
        // Centro di ricerca: baricentro pesato sulla potenza lineare
        var sw = 0.0; var cx = 0.0; var cy = 0.0
        val top = bins.maxOf { it.level }
        var extent = 0.0
        for (b in bins) {
            val w = 10.0.pow((b.level - top) / 10.0)
            sw += w; cx += w * b.x; cy += w * b.y
        }
        cx /= sw; cy /= sw
        for (b in bins) extent = max(extent, hypot(b.x - cx, b.y - cy))
        // In zona rurale i tralicci distano spesso 3-10 km; il TA, se c'è, dice quanto allargare
        val maxTa = bins.mapNotNull { it.ta }.maxOrNull() ?: 0.0
        val radius = max(5 * extent, maxTa + 1500.0).coerceIn(5000.0, 20000.0)
        val scale = min(bins.size.toDouble(), MAX_EFFECTIVE_N) / bins.size

        val steps = 40
        val step = radius / steps
        val coarse = ArrayList<DoubleArray>((2 * steps + 1) * (2 * steps + 1))
        var bestChi = Double.MAX_VALUE
        var bx = cx; var by = cy; var bn = 3.0
        for (i in -steps..steps) for (j in -steps..steps) {
            val x = cx + i * step
            val y = cy + j * step
            val (chi, n) = chi2(bins, x, y, scale)
            coarse += doubleArrayOf(x, y, chi, if (abs(i) == steps || abs(j) == steps) 1.0 else 0.0)
            if (chi < bestChi) { bestChi = chi; bx = x; by = y; bn = n }
        }
        // Raffinamento attorno al minimo
        val fine = step / 10
        val ox = bx; val oy = by
        for (i in -20..20) for (j in -20..20) {
            val x = ox + i * fine
            val y = oy + j * fine
            val (chi, n) = chi2(bins, x, y, scale)
            if (chi < bestChi) { bestChi = chi; bx = x; by = y; bn = n }
        }
        val region = coarse.filter { it[2] <= bestChi + DELTA_CHI2 }
        val touchesEdge = region.any { it[3] == 1.0 }
        return Fit(bx, by, bn, region.map { doubleArrayOf(it[0], it[1]) } + listOf(doubleArrayOf(bx, by)), touchesEdge)
    }

    /** χ² del modello log-distanza con trasmettitore in (x,y), minimizzato su n e P0. */
    private fun chi2(bins: List<Bin>, x: Double, y: Double, scale: Double): Pair<Double, Double> {
        var best = Double.MAX_VALUE
        var bestN = 3.0
        val logs = DoubleArray(bins.size)
        var taTerm = 0.0
        for ((k, b) in bins.withIndex()) {
            val d = max(hypot(b.x - x, b.y - y), MIN_DIST_M)
            logs[k] = log10(d)
            if (b.ta != null) {
                val e = (d - b.ta) / SIGMA_TA_M
                taTerm += b.weight * e * e
            }
        }
        for (n in exponents) {
            var sw = 0.0; var sp = 0.0
            for ((k, b) in bins.withIndex()) {
                sw += b.weight; sp += b.weight * (b.level + 10 * n * logs[k])
            }
            val p0 = sp / sw
            var sse = 0.0
            for ((k, b) in bins.withIndex()) {
                val r = b.level + 10 * n * logs[k] - p0
                sse += b.weight * r * r
            }
            // Prior debole: potenza a 1 m plausibile per una stazione radio base
            val prior = when {
                p0 < -40 -> ((-40 - p0) / 5).pow(2)
                p0 > 40 -> ((p0 - 40) / 5).pow(2)
                else -> 0.0
            }
            val chi = (sse / (SIGMA_DB * SIGMA_DB) + taTerm) * scale + prior
            if (chi < best) { best = chi; bestN = n }
        }
        return best to bestN
    }
}
