package it.vallequaranta.segnale.analysis

import it.vallequaranta.segnale.radio.CellMeasurement
import it.vallequaranta.segnale.radio.Operators
import it.vallequaranta.segnale.radio.Quality
import it.vallequaranta.segnale.radio.Tech

/** Una cella misurata in un punto. */
data class Record(val pos: LatLon, val cell: CellMeasurement)

/** Posizione di un traliccio inserita a mano (da catasto impianti, sopralluogo, ecc.). */
data class KnownSite(val key: String, val pos: LatLon, val label: String)

/** Riepilogo di un trasmettitore (sito 4G, cella 5G/3G/2G, o cella vicina senza ID). */
data class EntitySummary(
    val key: String,
    val tech: Tech,
    val plmn: String?,
    val operatorName: String,
    val label: String,
    val bands: Set<String>,
    val sectors: Set<Int>,
    val estimate: SiteEstimate?,
    val known: KnownSite?,
    /** Miglior livello entro il raggio della casa. */
    val bestNearHome: Int?,
    /** Punto migliore entro il raggio della casa: dove mettere l'antenna esterna. */
    val bestSpotNearHome: LatLon?,
) {
    /** Posizione da usare per il puntamento: quella nota se inserita, altrimenti la stimata. */
    val targetPosition: LatLon? get() = known?.pos ?: estimate?.location

    fun bearingFrom(ref: LatLon): Double? = targetPosition?.let { Geo.bearing(ref, it) }
    fun distanceFrom(ref: LatLon): Double? = targetPosition?.let { Geo.distance(ref, it) }
}

data class OperatorSummary(
    val plmn: String,
    val name: String,
    val entities: List<EntitySummary>,
    val bestLevel: Int,
    val bestNearHome: Int?,
    val medianNearHome: Int?,
    val samplesNearHome: Int,
    val bands: Set<String>,
    val techs: Set<Tech>,
) {
    /** Punteggio per la classifica: conta soprattutto il segnale 4G/5G attorno alla casa. */
    val score: Double
        get() = (bestNearHome ?: (bestLevel - 20)).toDouble() + (medianNearHome ?: (bestLevel - 30)) * 0.5
}

data class Report(
    val operators: List<OperatorSummary>,
    val entities: List<EntitySummary>,
    val home: LatLon?,
    val radiusM: Double,
    val totalSamples: Int,
) {
    fun recommendation(): String {
        if (operators.isEmpty()) return "Nessuna misura 4G/5G: esegui un rilievo camminando attorno alla casa."
        val best = operators.first()
        val sb = StringBuilder()
        val near = best.bestNearHome
        if (home == null) {
            sb.append("Imposta la posizione della casa (scheda Rilievo) per avere azimut e confronto nel raggio.\n\n")
        }
        sb.append("Operatore consigliato: ${best.name}")
        if (near != null) sb.append(" (migliore entro ${radiusM.toInt()} m: $near dBm, ${Quality.rsrpLabel(near)})")
        sb.append(".\n")
        val main = best.entities.firstOrNull { it.targetPosition != null && it.tech == Tech.LTE }
            ?: best.entities.firstOrNull { it.targetPosition != null }
        if (main != null && home != null) {
            val az = main.bearingFrom(home)!!
            val d = main.distanceFrom(home)!!
            sb.append("Punta l'antenna donatrice verso ${"%.0f".format(az)}° (${Geo.cardinal(az)}), ")
            sb.append("traliccio ${main.label} a circa ${formatDistance(d)}")
            main.estimate?.bearingSpread?.takeIf { main.known == null }?.let { sb.append(", incertezza ±${"%.0f".format(it)}°") }
            sb.append(".\n")
            main.bestSpotNearHome?.let {
                val dd = Geo.distance(home, it)
                if (dd > 5) sb.append("Punto migliore misurato vicino casa: ${formatDistance(dd)} verso ${Geo.cardinal(Geo.bearing(home, it))}.\n")
            }
        }
        if (near != null) {
            sb.append(
                when {
                    near >= -95 -> "Segnale esterno sufficiente per un sistema di amplificazione o un router 4G/5G con antenna esterna.\n"
                    near >= -110 -> "Segnale esterno debole: serve un'antenna direzionale ad alto guadagno (Yagi/pannello 10-15 dBi), montata in alto.\n"
                    else -> "Segnale molto debole anche all'esterno: valuta un punto più alto/distante per l'antenna o un altro operatore.\n"
                }
            )
        }
        if (best.bands.isNotEmpty()) sb.append("Bande da coprire: ${best.bands.sorted().joinToString(", ")}.\n")
        if (operators.size > 1) {
            val second = operators[1]
            sb.append("\nSeguono: ").append(operators.drop(1).joinToString(", ") { o ->
                o.name + (o.bestNearHome?.let { " ($it dBm)" } ?: "")
            }).append(".")
            if (second.bestNearHome != null && near != null && near - second.bestNearHome < 3) {
                sb.append(" Differenza minima con ${second.name}: decidi in base alle bande e ai costi.")
            }
        } else {
            sb.append("\nÈ stato misurato un solo operatore: per confrontarli ripeti il rilievo con SIM di altri operatori (anche prepagate).")
        }
        return sb.toString()
    }
}

fun formatDistance(m: Double): String = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)

object Analyzer {
    fun analyze(
        records: List<Record>,
        home: LatLon?,
        radiusM: Double,
        known: Map<String, KnownSite>,
    ): Report {
        val byEntity = records.groupBy { it.cell.entityKey }
        val entities = byEntity.map { (key, recs) ->
            val first = recs.first().cell
            val obs = recs.map { Obs(it.pos, it.cell.level, it.cell.timingAdvanceMeters) }
            val near = if (home != null) recs.filter { Geo.distance(home, it.pos) <= radiusM } else emptyList()
            val bestNear = near.maxByOrNull { it.cell.level }
            EntitySummary(
                key = key,
                tech = first.tech,
                plmn = first.plmn,
                operatorName = first.operatorName,
                label = label(first),
                bands = recs.mapNotNull { it.cell.band?.let { b -> "${b.name} (${b.commonName})" } }.toSortedSet(),
                sectors = recs.mapNotNull { it.cell.sector }.toSortedSet(),
                estimate = SiteEstimator.estimate(obs, home),
                known = known[key],
                bestNearHome = bestNear?.cell?.level,
                bestSpotNearHome = bestNear?.pos,
            )
        }.sortedByDescending { it.bestNearHome ?: (it.estimate?.maxLevel ?: -200) - 30 }

        // Classifica operatori solo su 4G/5G: RSRP confrontabili fra loro
        val broadband = records.filter { it.cell.tech == Tech.LTE || it.cell.tech == Tech.NR }
        val operators = broadband.groupBy { it.cell.plmn ?: "?" }.map { (plmn, recs) ->
            val near = if (home != null) recs.filter { Geo.distance(home, it.pos) <= radiusM } else emptyList()
            // Per punto di misura si considera la cella migliore dell'operatore
            val nearPerPoint = near.groupBy { it.pos }.map { (_, r) -> r.maxOf { it.cell.level } }.sorted()
            OperatorSummary(
                plmn = plmn,
                name = Operators.nameForPlmn(plmn),
                entities = entities.filter { it.plmn == plmn },
                bestLevel = recs.maxOf { it.cell.level },
                bestNearHome = nearPerPoint.lastOrNull(),
                medianNearHome = nearPerPoint.getOrNull(nearPerPoint.size / 2),
                samplesNearHome = nearPerPoint.size,
                bands = recs.mapNotNull { it.cell.band?.let { b -> "${b.name} ${b.commonName}" } }.toSortedSet(),
                techs = recs.map { it.cell.tech }.toSortedSet(),
            )
        }.sortedByDescending { it.score }

        return Report(operators, entities, home, radiusM, records.map { it.pos }.distinct().size)
    }

    fun label(c: CellMeasurement): String = when {
        c.tech == Tech.LTE && c.siteId != null -> "eNB ${c.siteId}"
        c.siteId != null -> "${c.tech.label} cella ${c.siteId}"
        c.channel != null || c.pci != null -> "${c.tech.label} PCI ${c.pci ?: "?"} ch ${c.channel ?: "?"}"
        else -> "${c.tech.label} NSA"
    }
}
