package io.github.witzig0hast.cmftrack

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File

/** Map layers (all free, OpenStreetMap based). */
enum class Layer(val label: String) {
    STANDARD("Standard"), TOPO("Topo"), CYCLE("Rad"), SATELLITE("Satellit");

    val source: OnlineTileSourceBase
        get() = when (this) {
            STANDARD -> TileSourceFactory.MAPNIK
            TOPO -> TileSourceFactory.OpenTopo
            CYCLE -> XYTileSource("CyclOSM", 0, 20, 256, ".png",
                arrayOf("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/", "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/", "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"),
                "© OpenStreetMap-Mitwirkende, CyclOSM")
            SATELLITE -> object : OnlineTileSourceBase("EsriImagery", 0, 19, 256, "",
                arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"), "Esri, Maxar, Earthstar Geographics") {
                override fun getTileURLString(index: Long) =
                    baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getY(index) + "/" + MapTileIndex.getX(index)
            }
        }
}

object Maps {
    /** Must run before the first MapView is created. Cache lives in the app's cache folder (no storage permission). */
    fun init(ctx: Context) {
        val c = Configuration.getInstance()
        c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        c.userAgentValue = ctx.packageName
        c.osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        c.osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
    }

    fun setup(map: MapView, layer: Layer) {
        map.setTileSource(layer.source)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.setHorizontalMapRepetitionEnabled(false)
        map.setVerticalMapRepetitionEnabled(false)
        map.minZoomLevel = 3.0
        if (map.overlays.none { it is CopyrightOverlay }) {
            map.overlays.add(CopyrightOverlay(map.context).apply { setTextSize(9) })
            map.overlays.add(ScaleBarOverlay(map).apply { setAlignBottom(true); setAlignRight(false) })
        }
    }

    fun geo(points: List<TrackPoint>) = points.map { GeoPoint(it.lat, it.lon) }

    /** Track coloured by pace, split into polylines of the same colour bucket (keeps the overlay count small). */
    fun paceTrack(map: MapView, points: List<TrackPoint>): List<Overlay> {
        if (points.size < 2) return emptyList()
        val colors = Pace.colors(points)
        val out = mutableListOf<Overlay>()
        // white casing below the coloured line for contrast on any map
        out += Polyline(map).apply { setPoints(geo(points)); outlinePaint.color = Color.WHITE; outlinePaint.strokeWidth = 16f; outlinePaint.strokeCap = Paint.Cap.ROUND; isEnabled = true; setOnClickListener { _, _, _ -> false } }
        var segStart = 0
        fun bucket(c: Int): Int { val hsv = FloatArray(3); Color.colorToHSV(c, hsv); return (hsv[0] / 20).toInt() }
        for (i in 1..colors.size) {
            if (i == colors.size || bucket(colors[i]) != bucket(colors[segStart])) {
                val seg = points.subList(segStart, minOf(i + 1, points.size))
                out += Polyline(map).apply {
                    setPoints(geo(seg)); outlinePaint.color = colors[segStart]; outlinePaint.strokeWidth = 10f
                    outlinePaint.strokeCap = Paint.Cap.ROUND; setOnClickListener { _, _, _ -> false }
                }
                segStart = i
            }
        }
        return out
    }

    fun plainTrack(map: MapView, points: List<GeoPoint>, color: Int, width: Float = 10f, dashed: Boolean = false) = Polyline(map).apply {
        setPoints(points); outlinePaint.color = color; outlinePaint.strokeWidth = width; outlinePaint.strokeCap = Paint.Cap.ROUND
        if (dashed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(24f, 18f), 0f)
        setOnClickListener { _, _, _ -> false }
    }

    fun fit(map: MapView, pts: List<GeoPoint>, padding: Int = 80) {
        if (pts.isEmpty()) return
        if (pts.size == 1) { map.controller.setZoom(16.0); map.controller.setCenter(pts[0]); return }
        val box = BoundingBox.fromGeoPointsSafe(pts)
        // MapView needs a layout before zooming to a box
        if (map.width > 0) map.zoomToBoundingBox(box.increaseByScale(1.15f), false, padding)
        else map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box.increaseByScale(1.15f), false, padding) }
    }
}

/** Start/finish dots and km numbers drawn on top of the track. */
class MarkersOverlay(private val points: List<TrackPoint>, private val showKm: Boolean = true) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val p = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val pr = map.projection
        if (showKm) {
            var next = 1000.0
            for (pt in points) {
                if (pt.distance >= next) {
                    pr.toPixels(GeoPoint(pt.lat, pt.lon), p)
                    fill.color = 0xEE141414.toInt(); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 22f, fill)
                    c.drawText("${(next / 1000).toInt()}", p.x.toFloat(), p.y + 9f, text)
                    next += 1000.0
                }
            }
        }
        pr.toPixels(GeoPoint(points.first().lat, points.first().lon), p)
        fill.color = Color.WHITE; c.drawCircle(p.x.toFloat(), p.y.toFloat(), 17f, fill)
        fill.color = 0xFF2EBD59.toInt(); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 12f, fill)
        pr.toPixels(GeoPoint(points.last().lat, points.last().lon), p)
        fill.color = Color.WHITE; c.drawCircle(p.x.toFloat(), p.y.toFloat(), 17f, fill)
        fill.color = 0xFF141414.toInt(); c.drawCircle(p.x.toFloat(), p.y.toFloat(), 12f, fill)
        fill.color = Color.WHITE; c.drawCircle(p.x.toFloat(), p.y.toFloat(), 5f, fill)
    }
}
