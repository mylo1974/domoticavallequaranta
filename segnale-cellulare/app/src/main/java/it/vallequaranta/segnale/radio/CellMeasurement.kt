package it.vallequaranta.segnale.radio

/** Tecnologia radio della cella. */
enum class Tech(val label: String) {
    GSM("2G"), UMTS("3G"), LTE("4G"), NR("5G");
}

/**
 * Una misura di una singola cella in un istante.
 *
 * I campi numerici assenti sono null (Android restituisce UNAVAILABLE quando il
 * modem non li fornisce, tipico per le celle vicine non registrate).
 *
 * @property level potenza principale in dBm: RSRP (4G), SS-RSRP (5G), RSCP (3G), RSSI (2G)
 * @property quality RSRQ (4G/5G) o Ec/No (3G) in dB
 * @property sinr SINR/RSSNR in dB
 * @property cellId ECI (4G), NCI (5G), CID (2G/3G)
 * @property pci Physical Cell ID (4G/5G), PSC (3G), BSIC (2G)
 * @property channel EARFCN / NR-ARFCN / UARFCN / ARFCN
 * @property timingAdvance anticipo temporale grezzo (solo cella servente)
 */
data class CellMeasurement(
    val tech: Tech,
    val mcc: String?,
    val mnc: String?,
    val registered: Boolean,
    val level: Int,
    val quality: Int?,
    val sinr: Int?,
    val cellId: Long?,
    val pci: Int?,
    val tac: Int?,
    val channel: Int?,
    val timingAdvance: Int?,
    val operatorAssumed: Boolean = false,
) {
    val plmn: String? get() = if (mcc != null && mnc != null) mcc + mnc else null

    val operatorName: String get() = Operators.name(mcc, mnc)

    val band: Band? get() = channel?.let { Bands.lookup(tech, it) }

    /**
     * Identificativo del sito (traliccio) quando ricavabile.
     * Per il 4G l'eNodeB ID è ECI >> 8: settori diversi dello stesso traliccio
     * condividono l'eNB, quindi vengono raggruppati.
     */
    val siteId: Long?
        get() = when {
            cellId == null -> null
            tech == Tech.LTE -> cellId shr 8
            else -> cellId
        }

    /** Settore (LTE: gli 8 bit bassi dell'ECI). */
    val sector: Int? get() = if (tech == Tech.LTE && cellId != null) (cellId and 0xFF).toInt() else null

    /**
     * Chiave che raggruppa le misure dello stesso trasmettitore nel tempo.
     * Le celle vicine spesso non riportano l'ID: si usa canale+PCI.
     */
    val entityKey: String
        get() {
            val op = plmn ?: "?"
            return if (siteId != null) "${tech.name}|$op|S$siteId"
            else "${tech.name}|$op|C${channel ?: "?"}|P${pci ?: "?"}"
        }

    /** Distanza stimata dal timing advance, in metri (null se non disponibile). */
    val timingAdvanceMeters: Double?
        get() = timingAdvance?.let {
            when (tech) {
                // LTE: 1 unità = 16 Ts = 0,52 µs andata/ritorno ≈ 78,12 m
                Tech.LTE -> it * 78.12
                // GSM: 1 unità ≈ 553,5 m
                Tech.GSM -> it * 553.5
                else -> null
            }
        }
}

/** Soglie qualitative dell'RSRP (4G/5G) usate in tutta l'app. */
object Quality {
    fun rsrpLabel(dbm: Int): String = when {
        dbm >= -80 -> "ottimo"
        dbm >= -90 -> "buono"
        dbm >= -100 -> "discreto"
        dbm >= -110 -> "scarso"
        else -> "pessimo"
    }

    /** Colore ARGB per la mappa: verde → rosso. */
    fun rsrpColor(dbm: Int): Int = when {
        dbm >= -80 -> 0xFF1B8E3E.toInt()
        dbm >= -90 -> 0xFF7CB342.toInt()
        dbm >= -100 -> 0xFFFDD835.toInt()
        dbm >= -110 -> 0xFFFB8C00.toInt()
        else -> 0xFFD32F2F.toInt()
    }
}
