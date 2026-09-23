package it.vallequaranta.segnale.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandsTest {
    @Test
    fun lteBandsUsateInItalia() {
        assertEquals("B20", Bands.lteBand(6300)!!.name)
        assertEquals(806.0, Bands.lteBand(6300)!!.downlinkMHz, 0.01)
        assertEquals("B3", Bands.lteBand(1850)!!.name)
        assertEquals("B1", Bands.lteBand(100)!!.name)
        assertEquals("B7", Bands.lteBand(3050)!!.name)
        assertEquals("B8", Bands.lteBand(3600)!!.name)
        assertEquals("B28", Bands.lteBand(9460)!!.name)
        assertEquals("B32", Bands.lteBand(10000)!!.name)
        assertNull(Bands.lteBand(70000))
    }

    @Test
    fun nrArfcn() {
        // 3,6 GHz: n78
        val b = Bands.nrBand(640000)!!
        assertEquals("n78", b.name)
        assertEquals(3600.0, b.downlinkMHz, 0.01)
        assertEquals("n28", Bands.nrBand(156000)!!.name) // 780 MHz
    }

    @Test
    fun umtsEGsm() {
        assertEquals("B1", Bands.umtsBand(10700)!!.name)
        assertEquals("B8", Bands.umtsBand(3011)!!.name)
        assertEquals("GSM900", Bands.gsmBand(50)!!.name)
        assertEquals("DCS1800", Bands.gsmBand(700)!!.name)
    }

    @Test
    fun operatori() {
        assertEquals("TIM", Operators.name("222", "01"))
        assertEquals("Iliad", Operators.nameForPlmn("22250"))
        assertEquals("999-99", Operators.name("999", "99"))
    }

    @Test
    fun sitoLte() {
        val c = CellMeasurement(Tech.LTE, "222", "10", true, -95, -10, 5, 123456L * 256 + 3, 12, 1, 6300, 4)
        assertEquals(123456L, c.siteId)
        assertEquals(3, c.sector)
        assertEquals(312.48, c.timingAdvanceMeters!!, 0.01)
        assertEquals("LTE|22210|S123456", c.entityKey)
    }
}
