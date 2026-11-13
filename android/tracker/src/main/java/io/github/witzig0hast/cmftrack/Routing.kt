package io.github.witzig0hast.cmftrack

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One turn instruction (from Valhalla). [index] = position in [PlannedRoute.points]. */
data class Maneuver(val type: Int, val text: String, val verbal: String, val index: Int, val street: String?) {
    /** Arrow for notifications / the watch. */
    val arrow: String
        get() = when (type) {
            9, 19, 23 -> "↗"; 10 -> "→"; 11 -> "↘"; 12, 13 -> "↩"; 14 -> "↙"; 15 -> "←"; 16, 20, 24 -> "↖"
            4, 5, 6 -> "🏁"; 26, 27 -> "⟳"; else -> "↑"
        }
    val isArrival get() = type in 4..6
}

data class PlannedRoute(
    val name: String,
    val points: List<Pair<Double, Double>>,
    val maneuvers: List<Maneuver> = emptyList(),
    val mode: String? = null,                 // pedestrian / bicycle, null for imported GPX
    val destination: Pair<Double, Double>? = null,
    val via: List<Pair<Double, Double>> = emptyList(),
    val seconds: Double? = null,
) {
    /** cumulative distance along the route for each point */
    val cumulative: DoubleArray by lazy {
        DoubleArray(points.size).also { c -> for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1].first, points[i - 1].second, points[i].first, points[i].second) }
    }
    val length get() = cumulative.lastOrNull() ?: 0.0

    /** (distance from the route in m, distance along the route of the closest point) */
    fun locate(lat: Double, lon: Double, hintAlong: Double? = null): Pair<Double, Double> {
        val kx = 111_195.0 * cos(lat * PI / 180); val ky = 111_195.0
        var bestD = Double.MAX_VALUE; var bestAlong = 0.0
        for (i in 1 until points.size) {
            // prefer the part of the route near where we were last time (loops/out-and-back routes)
            if (hintAlong != null && (cumulative[i] < hintAlong - 300 || cumulative[i - 1] > hintAlong + 600)) continue
            val ax = (points[i - 1].second - lon) * kx; val ay = (points[i - 1].first - lat) * ky
            val bx = (points[i].second - lon) * kx; val by = (points[i].first - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestD) { bestD = d; bestAlong = cumulative[i - 1] + t * sqrt(len2) }
        }
        if (bestD == Double.MAX_VALUE) return locate(lat, lon, null)
        return bestD to bestAlong
    }

    /** Next turn after [along] and the distance to it. */
    fun nextManeuver(along: Double): Pair<Maneuver, Double>? = maneuvers
        .firstOrNull { it.index > 0 && cumulative[it.index.coerceAtMost(points.lastIndex)] > along + 3 }
        ?.let { it to (cumulative[it.index.coerceAtMost(points.lastIndex)] - along) }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("mode", mode).put("seconds", seconds)
        .put("points", JSONArray(points.map { JSONArray(listOf(it.first, it.second)) }))
        .put("maneuvers", JSONArray(maneuvers.map { JSONObject().put("type", it.type).put("text", it.text).put("verbal", it.verbal).put("index", it.index).put("street", it.street) }))
        .put("destination", destination?.let { JSONArray(listOf(it.first, it.second)) })
        .put("via", JSONArray(via.map { JSONArray(listOf(it.first, it.second)) }))

    companion object {
        fun fromJson(o: JSONObject): PlannedRoute {
            fun pair(a: JSONArray) = a.getDouble(0) to a.getDouble(1)
            val pts = o.getJSONArray("points").let { a -> (0 until a.length()).map { pair(a.getJSONArray(it)) } }
            val man = o.optJSONArray("maneuvers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
                Maneuver(it.getInt("type"), it.getString("text"), it.optString("verbal", it.getString("text")), it.getInt("index"), it.optString("street").ifEmpty { null })
            } } ?: emptyList()
            return PlannedRoute(o.optString("name", "Route"), pts, man, o.optString("mode").ifEmpty { null },
                o.optJSONArray("destination")?.let(::pair), o.optJSONArray("via")?.let { a -> (0 until a.length()).map { pair(a.getJSONArray(it)) } } ?: emptyList(),
                if (o.has("seconds") && !o.isNull("seconds")) o.getDouble("seconds") else null)
        }

        /** Reads track points, route points or (as last resort) waypoints from a GPX file. */
        fun parseGpx(input: InputStream, fallbackName: String): PlannedRoute {
            val trk = mutableListOf<Pair<Double, Double>>(); val rte = mutableListOf<Pair<Double, Double>>(); val wpt = mutableListOf<Pair<Double, Double>>()
            var name: String? = null
            val p = Xml.newPullParser(); p.setInput(input, null)
            var inName = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "trkpt", "rtept", "wpt" -> {
                            val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull(); val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) when (p.name) { "trkpt" -> trk += lat to lon; "rtept" -> rte += lat to lon; else -> wpt += lat to lon }
                        }
                        "name" -> inName = name == null
                    }
                    XmlPullParser.TEXT -> if (inName) { name = p.text.trim().ifEmpty { null }; inName = false }
                    XmlPullParser.END_TAG -> if (p.name == "name") inName = false
                }
            }
            val pts = trk.ifEmpty { rte }.ifEmpty { wpt }
            require(pts.size >= 2) { "Die Datei enthält keine Route" }
            return PlannedRoute(name ?: fallbackName, pts)
        }
    }
}

/** The active route (used by the next recording) plus a few recently planned ones. */
object RouteStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "route.json")
    private fun recentFile(ctx: Context) = File(ctx.filesDir, "routes_recent.json")

    fun set(ctx: Context, r: PlannedRoute) {
        file(ctx).writeText(r.toJson().toString())
        val recent = recent(ctx).filter { it.name != r.name }.take(9)
        recentFile(ctx).writeText(JSONArray((listOf(r) + recent).map { it.toJson() }).toString())
    }

    /** GPX import (keeps the old call sites working). */
    fun save(ctx: Context, input: InputStream) = set(ctx, PlannedRoute.parseGpx(input, "Route"))

    fun load(ctx: Context): PlannedRoute? = runCatching { PlannedRoute.fromJson(JSONObject(file(ctx).readText())) }.getOrNull()
        ?: runCatching { File(ctx.filesDir, "route.gpx").inputStream().use { PlannedRoute.parseGpx(it, "Geplante Route") } }.getOrNull()

    fun recent(ctx: Context): List<PlannedRoute> = runCatching {
        val a = JSONArray(recentFile(ctx).readText()); (0 until a.length()).map { PlannedRoute.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) { file(ctx).delete(); File(ctx.filesDir, "route.gpx").delete() }
}

/** Free routing (Valhalla on the FOSSGIS server) and place search (Photon by Komoot), both OpenStreetMap. */
object Routing {
    var valhallaUrl = "https://valhalla1.openstreetmap.de/route"
    var photonUrl = "https://photon.komoot.io/api/"
    private const val UA = "CMF-Track/3.0 (Android; open source)"

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    fun costing(sport: Sport) = if (sport == Sport.RIDE || sport == Sport.INLINE) "bicycle" else "pedestrian"

    private fun http(url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code != 200) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotEmpty() }
                throw IOException(msg?.let { "Keine Route gefunden: $it" } ?: "Routing-Server: HTTP $code")
            }
            return text
        } finally { c.disconnect() }
    }

    /** Route through [stops] (start, optional via points, destination). */
    fun route(stops: List<Pair<Double, Double>>, sport: Sport, name: String, destination: Pair<Double, Double>? = stops.last()): PlannedRoute {
        val req = JSONObject()
            .put("locations", JSONArray(stops.mapIndexed { i, (lat, lon) ->
                JSONObject().put("lat", lat).put("lon", lon).put("type", if (i == 0 || i == stops.lastIndex) "break" else "through")
            }))
            .put("costing", costing(sport))
            .put("costing_options", JSONObject().put("pedestrian", JSONObject().put("walking_speed", if (sport == Sport.RUN) 9.0 else 5.1))
                .put("bicycle", JSONObject().put("bicycle_type", "Hybrid")))
            .put("language", "de-DE")
            .put("units", "kilometers")
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        return parse(http(valhallaUrl, req.toString()), name, costing(sport), destination, stops.drop(1).dropLast(1))
    }

    /** Valhalla JSON -> route (shape is polyline6, maneuver indices point into the shape). */
    fun parse(json: String, name: String, mode: String?, destination: Pair<Double, Double>?, via: List<Pair<Double, Double>> = emptyList()): PlannedRoute {
        val trip = JSONObject(json).getJSONObject("trip")
        val legs = trip.getJSONArray("legs")
        val points = mutableListOf<Pair<Double, Double>>()
        val maneuvers = mutableListOf<Maneuver>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val offset = if (points.isEmpty()) 0 else points.size - 1
            val shape = decodePolyline6(leg.getString("shape"))
            points += if (points.isEmpty()) shape else shape.drop(1)
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val type = m.getInt("type")
                if (l < legs.length() - 1 && type in 4..6) continue // arriving at a via point is not the end
                if (l > 0 && type in 1..3) continue
                maneuvers += Maneuver(type, m.getString("instruction"), m.optString("verbal_pre_transition_instruction", m.getString("instruction")),
                    offset + m.getInt("begin_shape_index"), m.optJSONArray("street_names")?.optString(0))
            }
        }
        return PlannedRoute(name, points, maneuvers, mode, destination, via, trip.getJSONObject("summary").optDouble("time"))
    }

    /** Round trip of roughly [km] kilometres from [start]: three via points on a circle, routed on real paths. */
    fun roundTrip(start: Pair<Double, Double>, km: Double, sport: Sport, directionDeg: Double): PlannedRoute {
        // paths are longer than the circle - aim a bit smaller and correct once
        var radius = km * 1000 / (2 * PI) * 0.78
        var best: PlannedRoute? = null
        repeat(2) {
            val via = (1..3).map { k ->
                val a = (directionDeg + 90 * k - 180) * PI / 180
                (start.first + radius * cos(a) / 111_195.0) to (start.second + radius * sin(a) / (111_195.0 * cos(start.first * PI / 180)))
            }
            val r = route(listOf(start) + via + listOf(start), sport, "Rundtour ${km.toInt()} km", destination = start)
            best = r
            val ratio = r.length / (km * 1000)
            if (ratio in 0.9..1.1) return r
            radius /= ratio
        }
        return best!!
    }

    fun search(query: String, near: Pair<Double, Double>?): List<Place> {
        var url = photonUrl + "?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=7&lang=de"
        near?.let { url += "&lat=${it.first}&lon=${it.second}" }
        return parsePlaces(http(url))
    }

    fun parsePlaces(json: String): List<Place> {
        val features = JSONObject(json).getJSONArray("features")
        return (0 until features.length()).map { features.getJSONObject(it) }.map { f ->
            val p = f.getJSONObject("properties"); val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val name = p.optString("name").ifEmpty { listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotEmpty() }.joinToString(" ") }
            val detail = listOf(p.optString("street").takeIf { p.has("name") }, p.optString("postcode"), p.optString("city"), p.optString("country"))
                .filter { !it.isNullOrEmpty() }.joinToString(", ")
            Place(name.ifEmpty { detail }, detail, c.getDouble(1), c.getDouble(0))
        }
    }

    fun decodePolyline6(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0; var result = 0L; var b: Int
                do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += d else lon += d
            }
            out += lat / 1e6 to lon / 1e6
        }
        return out
    }
}
