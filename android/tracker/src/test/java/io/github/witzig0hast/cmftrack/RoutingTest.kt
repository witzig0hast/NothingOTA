package io.github.witzig0hast.cmftrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses real responses recorded from valhalla1.openstreetmap.de and photon.komoot.io (src/test/resources). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoutingTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun valhallaRouteIsParsedWithTurns() {
        val r = Routing.parse(res("valhalla.json"), "Zum Alexanderplatz", "pedestrian", 52.52 to 13.405)
        // Brandenburger Tor -> Alexanderplatz area; Valhalla says 2.008 km
        assertEquals(52.5163, r.points.first().first, 0.001); assertEquals(13.3777, r.points.first().second, 0.001)
        assertEquals(52.5200, r.points.last().first, 0.001); assertEquals(13.4050, r.points.last().second, 0.001)
        assertEquals(2008.0, r.length, 40.0)
        assertTrue(r.maneuvers.size > 5)
        assertEquals("Rechts auf Wilhelmstraße/B 2 abbiegen.", r.maneuvers[5].text)
        assertEquals("→", r.maneuvers[5].arrow)
        assertTrue(r.maneuvers.last().isArrival)

        // walking along the route: next turn and distance to it
        val (m, dist) = r.nextManeuver(0.0)!!
        assertEquals(23, m.type); assertTrue("dist $dist", dist in 20.0..60.0)
        val p = r.points[60]
        val (off, along) = r.locate(p.first, p.second)
        assertTrue(off < 1); assertEquals(r.cumulative[60], along, 1.0)

        // JSON round trip keeps everything for the recording service
        val back = PlannedRoute.fromJson(r.toJson())
        assertEquals(r.points.size, back.points.size); assertEquals(r.maneuvers, back.maneuvers); assertEquals(r.destination, back.destination)
    }

    @Test
    fun photonPlacesAreParsed() {
        val places = Routing.parsePlaces(res("photon.json"))
        assertEquals("Brandenburger Tor", places[0].name)
        assertTrue(places[0].detail.contains("Berlin"))
        assertEquals(52.5163, places[0].lat, 0.001)
    }

    @Test
    fun polyline6() {
        // "_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI" encodes (38.5,-120.2),(40.7,-120.95),(43.252,-126.453) at precision 6
        val p = Routing.decodePolyline6("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI")
        assertEquals(listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453), p)
    }
}
