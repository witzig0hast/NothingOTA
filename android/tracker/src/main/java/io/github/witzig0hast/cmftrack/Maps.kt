package io.github.witzig0hast.cmftrack

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.FrameLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.json.JSONArray
import org.json.JSONObject

/** Map looks. Vector styles come from OpenFreeMap (free, no key, OpenStreetMap data). */
enum class Layer(val label: String) {
    STANDARD("Standard"), BRIGHT("Hell"), TOPO("Topo (Wandern)"), SATELLITE("Satellit");

    fun style(dark: Boolean): Style.Builder = when (this) {
        STANDARD -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/${if (dark) "dark" else "liberty"}")
        BRIGHT -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/${if (dark) "fiord" else "positron"}")
        TOPO -> Style.Builder().fromJson(raster(listOf("https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            "https://b.tile.opentopomap.org/{z}/{x}/{y}.png"), 17, "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap"))
        SATELLITE -> Style.Builder().fromJson(raster(listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
            19, "Esri, Maxar, Earthstar Geographics"))
    }

    private fun raster(tiles: List<String>, maxZoom: Int, attribution: String): String = JSONObject()
        .put("version", 8)
        .put("glyphs", "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf") // for our km labels
        .put("sources", JSONObject().put("r", JSONObject().put("type", "raster").put("tiles", JSONArray(tiles))
            .put("tileSize", 256).put("maxzoom", maxZoom).put("attribution", attribution)))
        .put("layers", JSONArray().put(JSONObject().put("id", "r").put("type", "raster").put("source", "r")))
        .toString()
}

/**
 * Vector map (MapLibre) with the layers the app needs: own track (single colour or pace gradient),
 * planned route, km markers, start/finish, own position. If the native map cannot start (e.g. in
 * unit tests) a simple [RouteView] is shown instead, so the screens keep working.
 */
class MapHost(private val container: FrameLayout, private var layer: Layer, savedState: Bundle? = null) {
    private val ctx: Context = container.context
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var fallback: RouteView? = null
    private val brand = ctx.getColor(R.color.brand)
    private val dark get() = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private var track: List<TrackPoint> = emptyList()
    private var paceColored = false
    private var planned: List<Pair<Double, Double>> = emptyList()
    private var me: Triple<Double, Double, Float>? = null
    private var kmMarkers = false
    var follow = true
    var onLongPress: ((Double, Double) -> Unit)? = null
    var onUserMoved: (() -> Unit)? = null

    val isVector get() = mapView != null

    init {
        try {
            MapLibre.getInstance(ctx)
            val mv = MapView(ctx)
            mv.onCreate(savedState)
            container.addView(mv, FrameLayout.LayoutParams(-1, -1))
            mapView = mv
            mv.getMapAsync { m ->
                map = m
                m.uiSettings.isCompassEnabled = true
                m.uiSettings.isRotateGesturesEnabled = true
                m.uiSettings.setAttributionMargins(16, 0, 0, 16)
                m.addOnMapLongClickListener { p -> onLongPress?.invoke(p.latitude, p.longitude); onLongPress != null }
                m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { follow = false; onUserMoved?.invoke() } }
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.1657, 10.4515), 5.0)) // Germany until we know more
                loadStyle()
            }
        } catch (t: Throwable) {
            mapView = null
            fallback = RouteView(ctx).also { container.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
    }

    private fun loadStyle() {
        val m = map ?: return
        style = null
        m.setStyle(layer.style(dark)) { s -> style = s; addLayers(s); refreshAll() }
    }

    fun setLayer(l: Layer) { layer = l; loadStyle() }

    // ------------------------------------------------------------------ data
    fun setTrack(points: List<TrackPoint>, paceColors: Boolean = false) {
        track = points; paceColored = paceColors
        fallback?.set(points)
        refreshTrack()
    }

    fun setPlanned(points: List<Pair<Double, Double>>) { planned = points; refreshPlanned() }
    fun showKmMarkers(on: Boolean) { kmMarkers = on; refreshTrack() }

    fun setMyLocation(lat: Double, lon: Double, accuracy: Float) {
        me = Triple(lat, lon, accuracy)
        refreshMe()
        if (follow) map?.let { m ->
            val z = if (m.cameraPosition.zoom < 13) 16.5 else m.cameraPosition.zoom
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), z), 600)
        }
    }

    fun recenter() {
        follow = true
        me?.let { (lat, lon, _) -> map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.5), 600) }
    }

    fun fit(points: List<Pair<Double, Double>>, padding: Int = 120) {
        val m = map ?: return
        if (points.size < 2) return
        follow = false
        val b = LatLngBounds.Builder().includes(points.map { LatLng(it.first, it.second) }).build()
        container.post { runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, padding)) } }
    }

    // ------------------------------------------------------------------ layers
    private fun addLayers(s: Style) {
        s.addSource(GeoJsonSource(PLANNED))
        s.addLayer(LineLayer("$PLANNED-casing", PLANNED).withProperties(P.lineColor(0xFFFFFFFF.toInt()), P.lineWidth(8f),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND), P.lineOpacity(0.9f)))
        s.addLayer(LineLayer(PLANNED, PLANNED).withProperties(P.lineColor(0xFF3B5BDB.toInt()), P.lineWidth(5f),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND), P.lineDasharray(arrayOf(1.5f, 1.2f))))
        s.addSource(GeoJsonSource(TRACK, GeoJsonOptions().withLineMetrics(true)))
        s.addLayer(LineLayer("$TRACK-casing", TRACK).withProperties(P.lineColor(0xFFFFFFFF.toInt()), P.lineWidth(10f),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(LineLayer(TRACK, TRACK).withProperties(P.lineColor(brand), P.lineWidth(6f),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addSource(GeoJsonSource(POINTS))
        s.addLayer(CircleLayer(POINTS, POINTS).withProperties(
            P.circleRadius(Expression.get("r")), P.circleColor(Expression.get("c")),
            P.circleStrokeColor(0xFFFFFFFF.toInt()), P.circleStrokeWidth(Expression.get("s"))))
        s.addLayer(SymbolLayer("$POINTS-label", POINTS).withProperties(
            P.textField(Expression.get("t")), P.textSize(11f), P.textColor(0xFFFFFFFF.toInt()),
            P.textFont(arrayOf("Noto Sans Bold")), P.textAllowOverlap(true), P.textIgnorePlacement(true)))
        s.addSource(GeoJsonSource(ME))
        s.addLayer(CircleLayer("$ME-acc", ME).withProperties(P.circleRadius(Expression.get("acc")), P.circleColor(0xFF4F8EF7.toInt()), P.circleOpacity(0.15f)))
        s.addLayer(CircleLayer(ME, ME).withProperties(P.circleRadius(8f), P.circleColor(0xFF4F8EF7.toInt()),
            P.circleStrokeColor(0xFFFFFFFF.toInt()), P.circleStrokeWidth(3f)))
    }

    private fun refreshAll() { refreshTrack(); refreshPlanned(); refreshMe() }

    private fun line(points: List<Pair<Double, Double>>): Feature =
        Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) }))

    private fun refreshTrack() {
        val s = style ?: return
        val pts = track
        (s.getSource(TRACK) as? GeoJsonSource)?.setGeoJson(
            if (pts.size >= 2) FeatureCollection.fromFeatures(listOf(line(pts.map { it.lat to it.lon }))) else FeatureCollection.fromFeatures(emptyList()))
        val layer = s.getLayer(TRACK) as? LineLayer
        if (paceColored && pts.size >= 2) {
            // colour along the line by pace (blue slow … red fast), sampled at up to 60 positions
            val colors = Pace.colors(pts)
            val total = pts.last().distance.coerceAtLeast(1.0)
            val stops = mutableListOf<Expression.Stop>()
            var last = -1.0
            for (i in colors.indices step maxOf(1, colors.size / 60)) {
                val f = ((pts[i + 1].distance) / total).coerceIn(0.0, 1.0)
                if (f > last + 1e-6) { stops += Expression.stop(f, Expression.color(colors[i])); last = f }
            }
            if (stops.isNotEmpty()) layer?.setProperties(P.lineGradient(Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stops.toTypedArray())))
        } else layer?.setProperties(P.lineColor(brand))
        // start, finish and km markers
        val features = mutableListOf<Feature>()
        if (kmMarkers) {
            var next = 1000.0
            for (p in pts) if (p.distance >= next) {
                features += point(p.lat, p.lon, 11f, 0xEE141414.toInt(), 0f, "${(next / 1000).toInt()}"); next += 1000.0
            }
        }
        if (pts.isNotEmpty() && kmMarkers) {
            features += point(pts.first().lat, pts.first().lon, 8f, 0xFF2EBD59.toInt(), 3f, "")
            features += point(pts.last().lat, pts.last().lon, 8f, 0xFF141414.toInt(), 3f, "")
        }
        (s.getSource(POINTS) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun point(lat: Double, lon: Double, r: Float, color: Int, stroke: Float, label: String): Feature =
        Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
            addNumberProperty("r", r); addStringProperty("c", String.format("#%06X", 0xFFFFFF and color))
            addNumberProperty("s", stroke); addStringProperty("t", label)
        }

    private fun refreshPlanned() {
        val s = style ?: return
        (s.getSource(PLANNED) as? GeoJsonSource)?.setGeoJson(
            if (planned.size >= 2) FeatureCollection.fromFeatures(listOf(line(planned))) else FeatureCollection.fromFeatures(emptyList()))
    }

    private fun refreshMe() {
        val s = style ?: return
        val m = me
        val metersPerPixel = map?.let { mm -> mm.projection.getMetersPerPixelAtLatitude(m?.first ?: 0.0) } ?: 1.0
        (s.getSource(ME) as? GeoJsonSource)?.setGeoJson(if (m == null) FeatureCollection.fromFeatures(emptyList()) else
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(m.second, m.first)).apply {
                addNumberProperty("acc", (m.third / metersPerPixel).coerceIn(8.0, 120.0))
            })))
    }

    // ------------------------------------------------------------------ lifecycle (forward from the activity)
    fun onStart() = mapView?.onStart()
    fun onResume() = mapView?.onResume()
    fun onPause() = mapView?.onPause()
    fun onStop() = mapView?.onStop()
    fun onDestroy() = mapView?.onDestroy()
    fun onLowMemory() = mapView?.onLowMemory()
    fun onSaveInstanceState(b: Bundle) = mapView?.onSaveInstanceState(b)

    companion object {
        private const val TRACK = "track"
        private const val PLANNED = "planned"
        private const val POINTS = "points"
        private const val ME = "me"
    }
}
