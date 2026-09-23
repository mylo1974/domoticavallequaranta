package it.vallequaranta.segnale.analysis

import it.vallequaranta.segnale.radio.CellMeasurement
import it.vallequaranta.segnale.radio.Tech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10

class SiteEstimatorTest {
    private val home = LatLon(45.0, 7.5)
    private val proj = LocalProjection(home)

    /** Rilievo sintetico: percorsi a croce e ad anello attorno alla casa. */
    private fun survey(tower: LatLon, n: Double, p0: Double, noise: Double, extent: Double, seed: Long): List<Obs> {
        val rnd = Random(seed)
        val t = proj.toXY(tower)
        val out = ArrayList<Obs>()
        var d = -extent
        while (d <= extent) {
            for ((x, y) in listOf(d to 0.0, 0.0 to d, d to d * 0.5, extent * 0.7 * Math.cos(d / extent * Math.PI) to extent * 0.7 * Math.sin(d / extent * Math.PI))) {
                val dist = Math.hypot(x - t[0], y - t[1])
                val lvl = p0 - 10 * n * log10(dist) + rnd.nextGaussian() * noise
                out += Obs(proj.toLatLon(x, y), lvl.toInt())
            }
            d += 5.0
        }
        return out
    }

    @Test
    fun geodesia() {
        val north = LatLon(46.0, 7.5)
        assertEquals(0.0, Geo.bearing(home, north), 0.01)
        assertEquals(111_195.0, Geo.distance(home, north), 100.0)
        assertEquals(90.0, Geo.bearing(home, LatLon(45.0, 7.6)), 0.1)
        assertEquals(-20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals("SO", Geo.cardinal(225.0))
        val p = proj.toLatLon(1000.0, -500.0)
        val xy = proj.toXY(p)
        assertEquals(1000.0, xy[0], 1e-6)
        assertEquals(-500.0, xy[1], 1e-6)
    }

    @Test
    fun trovaDirezioneTraliccioLontano() {
        // Traliccio a 2,5 km verso 60°, rilievo in un'area di ±400 m
        val tower = proj.toLatLon(2500 * Math.sin(Math.toRadians(60.0)), 2500 * Math.cos(Math.toRadians(60.0)))
        val est = SiteEstimator.estimate(survey(tower, 3.0, 5.0, 4.0, 400.0, 1), home)!!
        assertNotNull(est.gradientBearing)
        assertTrue("gradiente ${est.gradientBearing}", abs(Geo.angleDiff(est.gradientBearing!!, 60.0)) < 15)
        assertNotNull(est.bearingFromRef)
        assertTrue("azimut ${est.bearingFromRef}", abs(Geo.angleDiff(est.bearingFromRef!!, 60.0)) < 15)
    }

    @Test
    fun localizzaTraliccioVicino() {
        // Traliccio a 700 m verso 200°, rilievo ampio ±600 m: posizione ben determinata
        val tower = proj.toLatLon(700 * Math.sin(Math.toRadians(200.0)), 700 * Math.cos(Math.toRadians(200.0)))
        val est = SiteEstimator.estimate(survey(tower, 3.2, 10.0, 3.0, 600.0, 2), home)!!
        val err = Geo.distance(est.location!!, tower)
        assertTrue("errore posizione $err m", err < 150)
        assertTrue("azimut ${est.bearingFromRef}", abs(Geo.angleDiff(est.bearingFromRef!!, 200.0)) < 10)
        assertTrue(est.isWellConstrained)
    }

    @Test
    fun timingAdvanceVincolaLaDistanza() {
        val tower = proj.toLatLon(0.0, 3000.0)
        val obs = survey(tower, 3.0, 5.0, 4.0, 300.0, 3).map { it.copy(taMeters = 38 * 78.12) }
        val est = SiteEstimator.estimate(obs, home)!!
        assertTrue(est.usedTimingAdvance)
        assertTrue("distanza ${est.distanceFromRef}", abs(est.distanceFromRef!! - 3000) < 400)
        assertNotNull(est.distanceMax)
    }

    @Test
    fun classificaOperatori() {
        val rnd = Random(4)
        val records = ArrayList<Record>()
        for (i in 0 until 60) {
            val p = proj.toLatLon(rnd.nextDouble() * 160 - 80, rnd.nextDouble() * 160 - 80)
            records += Record(p, CellMeasurement(Tech.LTE, "222", "01", true, -112 + rnd.nextInt(4), null, null, 1000L * 256, 1, 1, 6300, null))
            records += Record(p, CellMeasurement(Tech.LTE, "222", "10", false, -98 + rnd.nextInt(4), null, null, 2000L * 256 + 1, 2, 1, 1850, null))
        }
        val r = Analyzer.analyze(records, home, 100.0, emptyMap())
        assertEquals("Vodafone", r.operators.first().name)
        assertTrue(r.recommendation().startsWith("Operatore consigliato: Vodafone"))
    }
}
