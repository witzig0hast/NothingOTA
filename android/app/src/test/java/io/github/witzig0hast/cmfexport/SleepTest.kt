package io.github.witzig0hast.cmfexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SleepTest {
    private val d = LocalDate.of(2026, 11, 10)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(day, java.time.LocalTime.of(h, m)).atZone(zone).toInstant()
    private fun s(a: java.time.Instant, b: java.time.Instant, deep: Double, light: Double, rem: Double, awake: Double, src: String = "cmf", stages: Boolean = true) =
        SleepNight(a, b, deep, light, rem, awake, src, stages)

    @Test
    fun napIsNotLastNightAndSplitNightIsMerged() {
        val seg1 = s(at(d.minusDays(1), 23, 0), at(d, 2, 30), 60.0, 120.0, 20.0, 10.0)     // 210 min
        val seg2 = s(at(d, 3, 0), at(d, 7, 0), 30.0, 150.0, 50.0, 10.0)                   // 240 min
        val dup = s(at(d, 3, 5), at(d, 6, 55), 0.0, 0.0, 0.0, 0.0, "other", stages = false) // same night, other app
        val nap = s(at(d, 14, 10), at(d, 14, 55), 0.0, 40.0, 0.0, 5.0)
        val g = Sleep.group(listOf(nap, seg2, dup, seg1))

        assertEquals(1, g.nights.size)
        val n = g.nights.single()
        assertEquals(2, n.segments)
        assertEquals(90.0, n.deep, 0.01); assertEquals(270.0, n.light, 0.01); assertEquals(70.0, n.rem, 0.01)
        assertEquals(50.0, n.awake, 0.01)               // 10 + 10 + 30 min lying awake between segments
        assertEquals(430.0, n.asleep, 0.01)
        assertEquals(d, Sleep.dayOf(n))
        assertEquals(1, g.naps.size); assertTrue(g.naps.single().nap)

        val days = Days().apply { addSleep(g) }
        val row = days.row(d, days.map[d]!!)
        assertEquals(430.0, row[days.columns.indexOf("sleep_min")])
        assertEquals(40.0, row[days.columns.indexOf("nap_min")])

        // the dashboard keeps the night even if a later-ending nap was stored before
        val stale = Dashboard(night = nap)
        val fresh = Dashboard(night = g.nights.last())
        assertEquals(n, stale.merge(fresh).night)
    }

    @Test
    fun noStageDataStillCountsCorrectly() {
        val a = s(at(d.minusDays(1), 22, 30), at(d, 1, 0), 0.0, 0.0, 0.0, 0.0, stages = false)
        val b = s(at(d, 1, 20), at(d, 6, 40), 0.0, 0.0, 0.0, 0.0, stages = false)
        val n = Sleep.group(listOf(a, b)).nights.single()
        assertEquals(150.0 + 320.0, n.asleep, 0.01)
        assertEquals(20.0, n.awake, 0.01)
    }

    @Test
    fun lateEveningNapStartsBelongToNextNightOnlyWhenLong() {
        // 19:30 for 30 min = nap; 21:30 to 06:00 = night of the next day
        val nap = s(at(d, 19, 30), at(d, 20, 0), 0.0, 25.0, 0.0, 5.0)
        val night = s(at(d, 21, 30), at(d.plusDays(1), 6, 0), 80.0, 300.0, 90.0, 40.0)
        val g = Sleep.group(listOf(nap, night))
        assertEquals(1, g.naps.size)
        assertEquals(d.plusDays(1), Sleep.dayOf(g.nights.single()))
        assertNull(Dashboard.napToday(Sleep.Grouped(emptyList(), emptyList())))
    }
}
