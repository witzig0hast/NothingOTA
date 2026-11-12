package io.github.witzig0hast.cmftrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** One recorded activity (summary). Times in epoch ms, distance in m. */
data class Activity(
    val id: Long = 0,
    val sport: Sport,
    val start: Long,
    val end: Long,
    val distance: Double = 0.0,
    val movingMs: Long = 0,
    val elevGain: Double = 0.0,
    val elevLoss: Double = 0.0,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val kcal: Double? = null,
    val maxSpeed: Double = 0.0,
    val splits: List<Split> = emptyList(),
    val hrSource: String? = null,
    val finished: Boolean = false,
) {
    val elapsedMs get() = end - start
    val avgSpeed get() = if (movingMs > 0) distance / (movingMs / 1000.0) else 0.0
    val title get() = "${sport.label} · ${Fmt.km(distance)} km"
}

class Store(ctx: Context) : SQLiteOpenHelper(ctx, "tracks.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, sport TEXT, start INTEGER, end INTEGER,
            distance REAL, moving_ms INTEGER, elev_gain REAL, elev_loss REAL, avg_hr INTEGER, max_hr INTEGER, kcal REAL,
            max_speed REAL, splits TEXT, hr_source TEXT, finished INTEGER)""")
        db.execSQL("CREATE TABLE point (activity INTEGER, t INTEGER, lat REAL, lon REAL, alt REAL, dist REAL, moving INTEGER)")
        db.execSQL("CREATE INDEX point_a ON point(activity, t)")
        db.execSQL("CREATE TABLE hr (activity INTEGER, t INTEGER, bpm INTEGER, PRIMARY KEY(activity, t))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun create(sport: Sport, start: Long): Long = writableDatabase.insert("activity", null, ContentValues().apply {
        put("sport", sport.name); put("start", start); put("end", start); put("finished", 0)
    })

    fun addPoint(a: Long, p: TrackPoint) = writableDatabase.insert("point", null, ContentValues().apply {
        put("activity", a); put("t", p.time); put("lat", p.lat); put("lon", p.lon); p.alt?.let { put("alt", it) }
        put("dist", p.distance); put("moving", if (p.moving) 1 else 0)
    })

    fun addHr(a: Long, t: Long, bpm: Int) = writableDatabase.insertWithOnConflict("hr", null, ContentValues().apply {
        put("activity", a); put("t", t); put("bpm", bpm)
    }, SQLiteDatabase.CONFLICT_REPLACE)

    fun replaceHr(a: Long, samples: List<Pair<Long, Int>>) = writableDatabase.run {
        beginTransaction()
        try {
            delete("hr", "activity=?", arrayOf(a.toString()))
            samples.forEach { (t, b) -> insert("hr", null, ContentValues().apply { put("activity", a); put("t", t); put("bpm", b) }) }
            setTransactionSuccessful()
        } finally { endTransaction() }
    }

    fun save(x: Activity) = writableDatabase.update("activity", ContentValues().apply {
        put("sport", x.sport.name); put("start", x.start); put("end", x.end); put("distance", x.distance)
        put("moving_ms", x.movingMs); put("elev_gain", x.elevGain); put("elev_loss", x.elevLoss)
        if (x.avgHr != null) put("avg_hr", x.avgHr) else putNull("avg_hr")
        if (x.maxHr != null) put("max_hr", x.maxHr) else putNull("max_hr")
        if (x.kcal != null) put("kcal", x.kcal) else putNull("kcal")
        put("max_speed", x.maxSpeed); put("splits", splitsJson(x.splits)); put("hr_source", x.hrSource)
        put("finished", if (x.finished) 1 else 0)
    }, "id=?", arrayOf(x.id.toString()))

    fun delete(id: Long) = writableDatabase.run {
        delete("point", "activity=?", arrayOf(id.toString())); delete("hr", "activity=?", arrayOf(id.toString()))
        delete("activity", "id=?", arrayOf(id.toString()))
    }

    fun list(): List<Activity> = readableDatabase.rawQuery("SELECT * FROM activity ORDER BY start DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(read(c)) }
    }

    fun get(id: Long): Activity? = readableDatabase.rawQuery("SELECT * FROM activity WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) read(c) else null
    }

    fun points(a: Long): List<TrackPoint> =
        readableDatabase.rawQuery("SELECT t, lat, lon, alt, dist, moving FROM point WHERE activity=? ORDER BY t", arrayOf(a.toString())).use { c ->
            buildList { while (c.moveToNext()) add(TrackPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3), c.getDouble(4), c.getInt(5) == 1)) }
        }

    fun heartRates(a: Long): List<Pair<Long, Int>> =
        readableDatabase.rawQuery("SELECT t, bpm FROM hr WHERE activity=? ORDER BY t", arrayOf(a.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getInt(1)) }
        }

    private fun read(c: android.database.Cursor): Activity {
        fun i(n: String) = c.getColumnIndexOrThrow(n)
        fun intOrNull(n: String) = if (c.isNull(i(n))) null else c.getInt(i(n))
        return Activity(
            c.getLong(i("id")), runCatching { Sport.valueOf(c.getString(i("sport"))) }.getOrDefault(Sport.OTHER),
            c.getLong(i("start")), c.getLong(i("end")), c.getDouble(i("distance")), c.getLong(i("moving_ms")),
            c.getDouble(i("elev_gain")), c.getDouble(i("elev_loss")), intOrNull("avg_hr"), intOrNull("max_hr"),
            if (c.isNull(i("kcal"))) null else c.getDouble(i("kcal")), c.getDouble(i("max_speed")),
            parseSplits(c.getString(i("splits"))), c.getString(i("hr_source")), c.getInt(i("finished")) == 1,
        )
    }

    companion object {
        fun splitsJson(s: List<Split>) = JSONArray(s.map {
            JSONObject().put("km", it.km).put("s", it.seconds).put("hr", it.avgHr ?: JSONObject.NULL).put("e", it.elevation)
        }).toString()

        fun parseSplits(s: String?): List<Split> = runCatching {
            val a = JSONArray(s!!)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Split(it.getInt("km"), it.getDouble("s"), if (it.isNull("hr")) null else it.getInt("hr"), it.getDouble("e"))
            }
        }.getOrDefault(emptyList())
    }
}
