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
        // L = 60, n = 5 → base 10 km, ±30 %
        val p = PoiSearchParams.from(60, 5)
        assertEquals(50, p.targetCount)
        assertEquals(7.0, p.minRadiusKm, 1e-9)
        assertEquals(13.0, p.maxRadiusKm, 1e-9)
    }

    @Test
    fun `single POI uses half the distance with 10 percent spread`() {
        val p = PoiSearchParams.from(50, 1)
        assertEquals(10, p.targetCount)
        assertEquals(22.5, p.minRadiusKm, 1e-9)
        assertEquals(27.5, p.maxRadiusKm, 1e-9)
    }

    @Test
    fun `stops in stage 1 when enough A and B are found and picks randomly`() = runBlocking {
        val source = FakeSource(List(80) { poi("a$it", "historic", "castle", km = 10.0) })
        val result = PoiSearcher(source, Random(1)).search(lat, lon, PoiSearchParams.from(60, 5))
        assertTrue(result.isComplete)
        assertEquals(PoiSearchStage.MAIN_AND_NATURE, result.stage)
        assertEquals(50, result.pois.size)
        assertEquals(1, result.requestCount)
    }

    @Test
    fun `skips POIs outside the ring`() = runBlocking {
        val inside = List(80) { poi("in$it", "historic", "castle", km = 5.0) }
        val outside = List(80) { poi("out$it", "historic", "castle", km = 15.0) }
        val result = PoiSearcher(FakeSource(inside + outside), Random(1)).search(lat, lon, PoiSearchParams.from(60, 5))
        assertFalse(result.isComplete)
        assertTrue(result.pois.isEmpty())
        assertEquals(3, result.requestCount)
    }

    @Test
    fun `later stage keeps all earlier POIs and fills randomly with the next class`() = runBlocking {
        val ab = List(10) { poi("a$it", "leisure", "park", km = 15.0) }
        val c = List(100) { poi("c$it", "natural", "peak", km = 15.0) }
        val result = PoiSearcher(FakeSource(ab + c), Random(1)).search(lat, lon, PoiSearchParams.from(60, 3))
        assertTrue(result.isComplete)
        assertEquals(PoiSearchStage.LOCAL, result.stage)
        assertEquals(30, result.pois.size)
        assertEquals(10, result.pois.count { it.poiClass == PoiClass.B })
        assertEquals(20, result.pois.count { it.poiClass == PoiClass.C })
    }

    @Test
    fun `stage 3 includes villages and reports an incomplete search`() = runBlocking {
        val villages = List(3) { poi("v$it", "place", "village", km = 10.0) }
        val result = PoiSearcher(FakeSource(villages), Random(1)).search(lat, lon, PoiSearchParams.from(60, 5))
        assertFalse(result.isComplete)
        assertEquals(3, result.pois.size)
        assertEquals(PoiSearchStage.FILLER, result.stage)
    }

    @Test
    fun `never recenters for 3 or fewer POIs`() = runBlocking {
        val ab = List(5) { poi("a$it", "leisure", "park", km = 15.0) }
        repeat(50) { seed ->
            val result = PoiSearcher(FakeSource(ab), Random(seed)).search(lat, lon, PoiSearchParams.from(80, 3))
            assertFalse(result.recentered)
            assertEquals(lat, result.centerLatitude, 0.0)
        }
    }

    @Test
    fun `sometimes recenters on a found POI and continues from there`() = runBlocking {
        // L = 60, n = 5 → ring 7–13 km. Parks 10 km north; peaks 20 km north are reachable only
        // from a park.
        val ab = List(5) { poi("a$it", "leisure", "park", km = 10.0) }
        val c = List(100) { poi("c$it", "natural", "peak", km = 20.0) }
        val results = (0 until 300).map { seed ->
            PoiSearcher(FakeSource(ab + c), Random(seed)).search(lat, lon, PoiSearchParams.from(60, 5))
        }
        val recentered = results.filter { it.recentered }
        assertTrue(recentered.size in 70..130) // ≈ 1/3 of 300
        recentered.forEach { r ->
            assertTrue(ab.any { it.latitude == r.centerLatitude && it.longitude == r.centerLongitude })
            assertTrue(r.isComplete)
            assertEquals(5, r.pois.count { it.poiClass == PoiClass.B })
            assertEquals(45, r.pois.count { it.poiClass == PoiClass.C })
        }
        results.filter { !it.recentered }.forEach { assertFalse(it.isComplete) }
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
    fun `stages cover A+B, C and D`() {
        assertEquals(3, PoiSearchStage.entries.size)
        assertTrue(PoiSearchStage.FILLER.rules.all { it.poiClass == PoiClass.D })
        assertTrue(PoiSearchStage.FILLER.rules.any { it.key == "place" })
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
