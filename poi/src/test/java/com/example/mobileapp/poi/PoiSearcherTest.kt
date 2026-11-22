package com.example.mobileapp.poi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PoiSearcherTest {

    private val lat = 50.0
    private val lon = 20.0

    @Test
    fun `params follow the formulas`() {
        // L = 50, n = 5 → F = 1
        val p = PoiSearchParams.from(50, 5)
        assertEquals(50, p.targetCount)
        assertEquals(3.0, p.startRadiusKm, 1e-9)
        assertEquals(9.0, p.maxRadiusKm, 1e-9)
        assertEquals(5.0, p.minRadiusKm, 1e-9)
        assertEquals(listOf(3.0, 4.5, 6.75, 9.0), p.radiusSteps)
    }

    @Test
    fun `params are clamped`() {
        val small = PoiSearchParams.from(5, 1)
        assertEquals(0.5, small.startRadiusKm, 1e-9)
        assertEquals(1.25, small.maxRadiusKm, 1e-9)
        assertEquals(0.5, small.minRadiusKm, 1e-9)

        val big = PoiSearchParams.from(150, 10)
        assertEquals(8.0, big.startRadiusKm, 1e-9)
        assertEquals(30.0, big.maxRadiusKm, 1e-9)
        assertEquals(10.0, big.minRadiusKm, 1e-9)
    }

    @Test
    fun `minimum radius never swallows the whole search area`() {
        val p = PoiSearchParams.from(50, 1) // min 5 km, max ≈ 4.02 km by the formulas
        assertTrue(p.minRadiusKm < p.maxRadiusKm)
    }

    @Test
    fun `stops in stage 1 when enough A and B are found and picks randomly`() = runBlocking {
        val source = FakeSource(List(80) { poi("a$it", "historic", "castle", km = 6.0) })
        val result = PoiSearcher(source, Random(1)).search(lat, lon, PoiSearchParams.from(50, 5))
        assertTrue(result.isComplete)
        assertEquals(PoiSearchStage.MAIN_AND_NATURE, result.stage)
        assertEquals(50, result.pois.size)
        assertEquals(6.75, result.radiusKm, 1e-9) // 3 and 4.5 km are inside the 5 km minimum
    }

    @Test
    fun `skips POIs inside the minimum radius`() = runBlocking {
        val source = FakeSource(List(80) { poi("a$it", "historic", "castle", km = 1.0) })
        val result = PoiSearcher(source, Random(1)).search(lat, lon, PoiSearchParams.from(50, 5))
        assertFalse(result.isComplete)
        assertTrue(result.pois.isEmpty())
    }

    @Test
    fun `later stage keeps all earlier POIs and fills with the next class`() = runBlocking {
        val ab = List(10) { poi("a$it", "leisure", "park", km = 8.0) }
        val c = List(100) { poi("c$it", "natural", "peak", km = 6.0) }
        val result = PoiSearcher(FakeSource(ab + c), Random(1)).search(lat, lon, PoiSearchParams.from(50, 5))
        assertTrue(result.isComplete)
        assertEquals(PoiSearchStage.LOCAL, result.stage)
        // Stage 1 finds only 10 parks; stage 2 restarts and at 6.75 km the 100 peaks exceed the
        // target while the parks (8 km) are not yet in range.
        assertEquals(6.75, result.radiusKm, 1e-9)
        assertEquals(50, result.pois.size)
        assertTrue(result.pois.all { it.poiClass == PoiClass.C })
    }

    @Test
    fun `earlier POIs within radius are all kept`() = runBlocking {
        val ab = List(10) { poi("a$it", "leisure", "park", km = 6.0) }
        val c = List(100) { poi("c$it", "natural", "peak", km = 6.0) }
        val result = PoiSearcher(FakeSource(ab + c), Random(1)).search(lat, lon, PoiSearchParams.from(50, 5))
        assertEquals(50, result.pois.size)
        assertEquals(10, result.pois.count { it.poiClass == PoiClass.B })
    }

    @Test
    fun `falls back to villages and reports an incomplete search`() = runBlocking {
        val villages = List(3) { poi("v$it", "place", "village", km = 7.0) }
        val result = PoiSearcher(FakeSource(villages), Random(1)).search(lat, lon, PoiSearchParams.from(50, 5))
        assertFalse(result.isComplete)
        assertEquals(3, result.pois.size)
        assertEquals(PoiSearchStage.VILLAGES, result.stage)
    }

    @Test
    fun `query cuts out the inner disc and limits output`() {
        val q = OverpassPoiSource.buildQuery(lat, lon, PoiSearchStage.LOCAL.rules, 4500, 3000, 150)
        assertTrue(q.contains("(around:4500,50.000000,20.000000)"))
        assertTrue(q.contains("nwr.ring(around:3000,50.000000,20.000000)->.inner;"))
        assertTrue(q.contains("(.ring; - .inner;)->.ring;"))
        assertTrue(q.endsWith(".ring out center tags 150;"))
        assertFalse(q.contains("castle"))
    }

    @Test
    fun `stage 3 does not query villages, stage 4 only villages`() {
        assertTrue(PoiSearchStage.FILLER.rules.none { it.key == "place" })
        assertTrue(PoiSearchStage.VILLAGES.rules.all { it.key == "place" })
    }

    /** POI placed [km] north of the search center. */
    private fun poi(id: String, key: String, value: String, km: Double): Poi {
        val tags = mapOf(key to value, "name" to id)
        val (rule, poiClass) = PoiCategories.classify(tags)!!
        return Poi(id, id, rule.tag, null, lat + km / 111.195, lon, poiClass, tags)
    }

    /** Serves [all] like Overpass would: by tag and ring, cut at the limit. */
    private inner class FakeSource(private val all: List<Poi>) : PoiSource {
        override suspend fun findPois(
            latitude: Double, longitude: Double, rules: List<PoiTagRule>,
            outerRadiusMeters: Int, innerRadiusMeters: Int, limit: Int?,
        ): PoiBatch {
            val matching = all.filter { poi ->
                val d = poi.distanceKmTo(latitude, longitude) * 1000
                rules.any { it.matches(poi.tags) } && d <= outerRadiusMeters && d > innerRadiusMeters
            }
            val limited = if (limit != null) matching.take(limit) else matching
            return PoiBatch(limited, limitReached = limit != null && matching.size >= limit)
        }
    }
}
