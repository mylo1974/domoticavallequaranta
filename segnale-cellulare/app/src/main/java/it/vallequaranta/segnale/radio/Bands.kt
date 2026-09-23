package it.vallequaranta.segnale.radio

/**
 * Banda radio con frequenza di downlink.
 * @property name nome 3GPP (es. "B20", "n78")
 * @property commonName nome commerciale (es. "800 MHz")
 */
data class Band(val name: String, val commonName: String, val downlinkMHz: Double)

/**
 * Conversione canale → banda/frequenza secondo 3GPP TS 36.101 (LTE),
 * TS 38.104 (NR), TS 25.101 (UMTS) e TS 45.005 (GSM).
 * Serve a scegliere un amplificatore che copra davvero le bande in uso.
 */
object Bands {
    private data class LteBand(val n: Int, val fDlLow: Double, val nOffs: Int, val nMax: Int, val common: String)

    private val lte = listOf(
        LteBand(1, 2110.0, 0, 599, "2100 MHz"),
        LteBand(2, 1930.0, 600, 1199, "1900 MHz"),
        LteBand(3, 1805.0, 1200, 1949, "1800 MHz"),
        LteBand(4, 2110.0, 1950, 2399, "AWS"),
        LteBand(5, 869.0, 2400, 2649, "850 MHz"),
        LteBand(7, 2620.0, 2750, 3449, "2600 MHz"),
        LteBand(8, 925.0, 3450, 3799, "900 MHz"),
        LteBand(12, 729.0, 5010, 5179, "700 MHz a"),
        LteBand(13, 746.0, 5180, 5279, "700 MHz c"),
        LteBand(17, 734.0, 5730, 5849, "700 MHz b"),
        LteBand(20, 791.0, 6150, 6449, "800 MHz"),
        LteBand(25, 1930.0, 8040, 8689, "1900 MHz+"),
        LteBand(28, 758.0, 9210, 9659, "700 MHz"),
        LteBand(32, 1452.0, 9920, 10359, "1500 MHz SDL"),
        LteBand(38, 2570.0, 37750, 38249, "2600 MHz TDD"),
        LteBand(40, 2300.0, 38650, 39649, "2300 MHz TDD"),
        LteBand(42, 3400.0, 41590, 43589, "3500 MHz TDD"),
        LteBand(43, 3600.0, 43590, 45589, "3700 MHz TDD"),
        LteBand(66, 2110.0, 66436, 67335, "AWS-3"),
        LteBand(71, 617.0, 68586, 68935, "600 MHz"),
    )

    // Bande NR in ordine di preferenza (le prime vincono in caso di sovrapposizione)
    private data class NrBand(val name: String, val lo: Double, val hi: Double, val common: String)

    private val nr = listOf(
        NrBand("n28", 758.0, 803.0, "700 MHz"),
        NrBand("n20", 791.0, 821.0, "800 MHz"),
        NrBand("n8", 925.0, 960.0, "900 MHz"),
        NrBand("n75", 1432.0, 1517.0, "1500 MHz SDL"),
        NrBand("n3", 1805.0, 1880.0, "1800 MHz"),
        NrBand("n1", 2110.0, 2170.0, "2100 MHz"),
        NrBand("n38", 2570.0, 2620.0, "2600 MHz TDD"),
        NrBand("n7", 2620.0, 2690.0, "2600 MHz"),
        NrBand("n78", 3300.0, 3800.0, "3500 MHz"),
        NrBand("n77", 3300.0, 4200.0, "3700 MHz"),
        NrBand("n258", 24250.0, 27500.0, "26 GHz"),
    )

    fun lookup(tech: Tech, channel: Int): Band? = when (tech) {
        Tech.LTE -> lteBand(channel)
        Tech.NR -> nrBand(channel)
        Tech.UMTS -> umtsBand(channel)
        Tech.GSM -> gsmBand(channel)
    }

    fun lteBand(earfcn: Int): Band? {
        val b = lte.firstOrNull { earfcn in it.nOffs..it.nMax } ?: return null
        return Band("B${b.n}", b.common, b.fDlLow + 0.1 * (earfcn - b.nOffs))
    }

    /** Frequenza NR dal NR-ARFCN (raster globale, TS 38.104 §5.4.2.1). */
    fun nrFrequencyMHz(arfcn: Int): Double? = when (arfcn) {
        in 0..599_999 -> 0.005 * arfcn
        in 600_000..2_016_666 -> 3000.0 + 0.015 * (arfcn - 600_000)
        in 2_016_667..3_279_165 -> 24250.08 + 0.06 * (arfcn - 2_016_667)
        else -> null
    }

    fun nrBand(arfcn: Int): Band? {
        val f = nrFrequencyMHz(arfcn) ?: return null
        val b = nr.firstOrNull { f >= it.lo && f <= it.hi } ?: return Band("n?", "%.0f MHz".format(f), f)
        return Band(b.name, b.common, f)
    }

    fun umtsBand(uarfcn: Int): Band? = when (uarfcn) {
        in 10562..10838 -> Band("B1", "2100 MHz", uarfcn / 5.0)
        in 2937..3088 -> Band("B8", "900 MHz", uarfcn / 5.0 + 340.0)
        in 1162..1513 -> Band("B3", "1800 MHz", uarfcn / 5.0 + 1575.0)
        else -> null
    }

    fun gsmBand(arfcn: Int): Band? = when (arfcn) {
        in 1..124 -> Band("GSM900", "900 MHz", 935.0 + 0.2 * arfcn)
        in 975..1023 -> Band("E-GSM900", "900 MHz", 935.0 + 0.2 * (arfcn - 1024))
        in 512..885 -> Band("DCS1800", "1800 MHz", 1805.2 + 0.2 * (arfcn - 512))
        else -> null
    }
}
