package it.vallequaranta.segnale.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.vallequaranta.segnale.analysis.KnownSite
import it.vallequaranta.segnale.analysis.LatLon
import it.vallequaranta.segnale.analysis.Record
import it.vallequaranta.segnale.radio.CellMeasurement
import it.vallequaranta.segnale.radio.Tech

/** Un punto di rilievo: posizione GPS + tutte le celle viste in quel momento. */
data class Sample(
    val time: Long,
    val pos: LatLon,
    val accuracy: Float,
    val altitude: Double?,
    val cells: List<CellMeasurement>,
)

/**
 * Archivio locale dei rilievi. I dati restano sul telefono: contengono la
 * posizione della casa e non vanno pubblicati.
 */
class SurveyDb(context: Context) : SQLiteOpenHelper(context, "rilievi.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sample (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
                acc REAL NOT NULL, alt REAL)"""
        )
        db.execSQL(
            """CREATE TABLE cell (
                sample_id INTEGER NOT NULL REFERENCES sample(id) ON DELETE CASCADE,
                tech TEXT NOT NULL, mcc TEXT, mnc TEXT, registered INTEGER NOT NULL,
                level INTEGER NOT NULL, quality INTEGER, sinr INTEGER,
                cell_id INTEGER, pci INTEGER, tac INTEGER, channel INTEGER, ta INTEGER,
                assumed INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX cell_sample ON cell(sample_id)")
        db.execSQL(
            """CREATE TABLE known_site (
                key TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, label TEXT NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun insert(s: Sample) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("sample", null, ContentValues().apply {
                put("ts", s.time); put("lat", s.pos.lat); put("lon", s.pos.lon)
                put("acc", s.accuracy); put("alt", s.altitude)
            })
            for (c in s.cells) {
                db.insert("cell", null, ContentValues().apply {
                    put("sample_id", id); put("tech", c.tech.name)
                    put("mcc", c.mcc); put("mnc", c.mnc)
                    put("registered", if (c.registered) 1 else 0)
                    put("level", c.level); put("quality", c.quality); put("sinr", c.sinr)
                    put("cell_id", c.cellId); put("pci", c.pci); put("tac", c.tac)
                    put("channel", c.channel); put("ta", c.timingAdvance)
                    put("assumed", if (c.operatorAssumed) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun sampleCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sample", null).use { it.moveToFirst(); it.getInt(0) }

    fun samples(): List<Sample> {
        val db = readableDatabase
        val cells = HashMap<Long, MutableList<CellMeasurement>>()
        db.rawQuery("SELECT * FROM cell", null).use { c ->
            while (c.moveToNext()) {
                val cell = CellMeasurement(
                    tech = Tech.valueOf(c.str("tech")!!),
                    mcc = c.str("mcc"), mnc = c.str("mnc"),
                    registered = c.int("registered") == 1,
                    level = c.int("level")!!, quality = c.int("quality"), sinr = c.int("sinr"),
                    cellId = c.long("cell_id"), pci = c.int("pci"), tac = c.int("tac"),
                    channel = c.int("channel"), timingAdvance = c.int("ta"),
                    operatorAssumed = c.int("assumed") == 1,
                )
                cells.getOrPut(c.long("sample_id")!!) { ArrayList() } += cell
            }
        }
        val out = ArrayList<Sample>()
        db.rawQuery("SELECT * FROM sample ORDER BY ts", null).use { c ->
            while (c.moveToNext()) {
                out += Sample(
                    time = c.long("ts")!!,
                    pos = LatLon(c.double("lat")!!, c.double("lon")!!),
                    accuracy = c.double("acc")!!.toFloat(),
                    altitude = c.double("alt"),
                    cells = cells[c.long("id")!!].orEmpty(),
                )
            }
        }
        return out
    }

    fun records(): List<Record> = samples().flatMap { s -> s.cells.map { Record(s.pos, it) } }

    fun clear() {
        writableDatabase.apply {
            delete("cell", null, null)
            delete("sample", null, null)
        }
    }

    fun knownSites(): Map<String, KnownSite> {
        val out = HashMap<String, KnownSite>()
        readableDatabase.rawQuery("SELECT * FROM known_site", null).use { c ->
            while (c.moveToNext()) {
                val k = c.str("key")!!
                out[k] = KnownSite(k, LatLon(c.double("lat")!!, c.double("lon")!!), c.str("label")!!)
            }
        }
        return out
    }

    fun saveKnownSite(s: KnownSite) {
        writableDatabase.insertWithOnConflict("known_site", null, ContentValues().apply {
            put("key", s.key); put("lat", s.pos.lat); put("lon", s.pos.lon); put("label", s.label)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteKnownSite(key: String) {
        writableDatabase.delete("known_site", "key = ?", arrayOf(key))
    }

    private fun Cursor.idx(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.str(n: String): String? = idx(n).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(n: String): Int? = idx(n).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.long(n: String): Long? = idx(n).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.double(n: String): Double? = idx(n).let { if (isNull(it)) null else getDouble(it) }
}
