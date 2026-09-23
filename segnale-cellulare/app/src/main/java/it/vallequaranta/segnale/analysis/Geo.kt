package it.vallequaranta.segnale.analysis

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Funzioni geodetiche su sfera (errore < 0,5% sulle distanze di interesse, pochi km). */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Azimut geografico (nord vero, 0-360° in senso orario) da [from] verso [to]. */
    fun bearing(from: LatLon, to: LatLon): Double {
        val p1 = rad(from.lat)
        val p2 = rad(to.lat)
        val dl = rad(to.lon - from.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(deg(atan2(y, x)))
    }

    fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Differenza angolare con segno in (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = normalize(a - b)
        if (d > 180) d -= 360
        return d
    }

    fun cardinal(deg: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
        return names[((normalize(deg) + 11.25) / 22.5).toInt() % 16]
    }
}

/**
 * Proiezione locale piana (est/nord in metri) attorno a un'origine.
 * Adeguata per aree di qualche decina di km.
 */
class LocalProjection(val origin: LatLon) {
    private val mPerDegLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_M
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(origin.lat))

    fun toXY(p: LatLon): DoubleArray =
        doubleArrayOf((p.lon - origin.lon) * mPerDegLon, (p.lat - origin.lat) * mPerDegLat)

    fun toLatLon(x: Double, y: Double): LatLon =
        LatLon(origin.lat + y / mPerDegLat, origin.lon + x / mPerDegLon)
}
