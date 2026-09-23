package it.vallequaranta.segnale.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import it.vallequaranta.segnale.analysis.Geo
import it.vallequaranta.segnale.analysis.Report
import it.vallequaranta.segnale.analysis.formatDistance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Esporta i rilievi nella cartella Download del telefono:
 * - CSV con tutte le misure (per fogli di calcolo);
 * - KML con casa, tralicci e linee di puntamento (per Google Earth, utile
 *   a controllare il profilo del terreno lungo la linea casa → traliccio).
 */
object Exporter {
    private val stamp get() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ITALY).format(Date())

    fun csv(samples: List<Sample>): String {
        val sb = StringBuilder()
        sb.append("timestamp;lat;lon;accuratezza_m;alt_m;tecnologia;operatore;mcc;mnc;registrata;livello_dbm;qualita_db;sinr_db;cell_id;sito;settore;pci;tac;canale;banda;freq_mhz;ta\n")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY)
        for (s in samples) for (c in s.cells) {
            val b = c.band
            sb.append(listOf(
                iso.format(Date(s.time)), "%.6f".format(Locale.ROOT, s.pos.lat), "%.6f".format(Locale.ROOT, s.pos.lon),
                "%.0f".format(Locale.ROOT, s.accuracy), s.altitude?.let { "%.0f".format(Locale.ROOT, it) } ?: "",
                c.tech.label, c.operatorName, c.mcc ?: "", c.mnc ?: "", if (c.registered) "1" else "0",
                c.level, c.quality ?: "", c.sinr ?: "", c.cellId ?: "", c.siteId ?: "", c.sector ?: "",
                c.pci ?: "", c.tac ?: "", c.channel ?: "", b?.name ?: "", b?.let { "%.1f".format(Locale.ROOT, it.downlinkMHz) } ?: "",
                c.timingAdvance ?: "",
            ).joinToString(";")).append('\n')
        }
        return sb.toString()
    }

    fun kml(report: Report): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Tralicci stimati</name>
<Style id="casa"><IconStyle><color>ff00ff00</color></IconStyle></Style>
<Style id="sito"><IconStyle><color>ff0000ff</color></IconStyle></Style>
<Style id="linea"><LineStyle><color>ff00ffff</color><width>3</width></LineStyle></Style>
""")
        val home = report.home
        if (home != null) {
            sb.append("<Placemark><name>Casa</name><styleUrl>#casa</styleUrl><Point><coordinates>${home.lon},${home.lat},0</coordinates></Point></Placemark>\n")
        }
        for (e in report.entities) {
            val p = e.targetPosition ?: continue
            val desc = StringBuilder("${e.operatorName} ${e.tech.label} ${e.bands.joinToString()}")
            if (home != null) {
                val az = Geo.bearing(home, p)
                desc.append(" | azimut ${"%.0f".format(az)}° ${Geo.cardinal(az)}, ${formatDistance(Geo.distance(home, p))}")
            }
            desc.append(if (e.known != null) " | posizione nota" else " | posizione stimata")
            sb.append("<Placemark><name>${esc(e.operatorName + " " + e.label)}</name><description>${esc(desc.toString())}</description>")
            sb.append("<styleUrl>#sito</styleUrl><Point><coordinates>${p.lon},${p.lat},0</coordinates></Point></Placemark>\n")
            if (home != null) {
                sb.append("<Placemark><name>${esc("Linea " + e.label)}</name><styleUrl>#linea</styleUrl><LineString><tessellate>1</tessellate>")
                sb.append("<coordinates>${home.lon},${home.lat},0 ${p.lon},${p.lat},0</coordinates></LineString></Placemark>\n")
            }
        }
        sb.append("</Document></kml>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Salva in Download/MappaSegnale e restituisce il nome del file. */
    fun saveToDownloads(context: Context, baseName: String, ext: String, mime: String, content: String): String {
        val name = "$baseName-$stamp.$ext"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MappaSegnale")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("impossibile creare il file")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out).write(content.toByteArray(Charsets.UTF_8))
        }
        return "Download/MappaSegnale/$name"
    }
}
