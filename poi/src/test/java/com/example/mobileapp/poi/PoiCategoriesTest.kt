package com.example.mobileapp.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiCategoriesTest {

    private fun classOf(vararg tags: Pair<String, String>) = PoiCategories.classify(tags.toMap())?.second

    @Test
    fun `classifies example tags from each class`() {
        assertEquals(PoiClass.A, classOf("historic" to "castle"))
        assertEquals(PoiClass.A, classOf("tourism" to "museum"))
        assertEquals(PoiClass.B, classOf("leisure" to "nature_reserve"))
        assertEquals(PoiClass.B, classOf("waterfall" to "yes"))
        assertEquals(PoiClass.B, classOf("waterway" to "waterfall"))
        assertEquals(PoiClass.C, classOf("natural" to "peak"))
        assertEquals(PoiClass.C, classOf("historic" to "wayside_shrine"))
        assertEquals(PoiClass.D, classOf("historic" to "wayside_cross"))
        assertEquals(PoiClass.D, classOf("place" to "village"))
    }

    @Test
    fun `unrelated element is not a poi`() {
        assertNull(classOf("amenity" to "parking"))
        assertNull(classOf("man_made" to "tower")) // not an observation tower
    }

    @Test
    fun `required extra tag is checked`() {
        assertEquals(PoiClass.B, classOf("man_made" to "tower", "tower:type" to "observation"))
    }

    @Test
    fun `best class wins when several tags match`() {
        assertEquals(PoiClass.A, classOf("historic" to "ruins", "tourism" to "attraction"))
    }

    @Test
    fun `notable artwork is a main attraction, other artwork is filler`() {
        assertEquals(PoiClass.D, classOf("tourism" to "artwork"))
        assertEquals(PoiClass.A, classOf("tourism" to "artwork", "wikidata" to "Q1"))
    }

    @Test
    fun `memorial plaques are fillers`() {
        assertEquals(PoiClass.C, classOf("historic" to "memorial"))
        assertEquals(PoiClass.D, classOf("historic" to "memorial", "memorial" to "plaque"))
    }

    @Test
    fun `query contains every rule`() {
        val query = OverpassPoiSource.buildQuery(50.0, 20.0, 10_000)
        assertTrue(query.startsWith("[out:json]"))
        assertTrue(query.contains("(around:10000,50.000000,20.000000)"))
        PoiCategories.rules.forEach { assertTrue(it.tag, query.contains(it.value)) }
        assertTrue(query.contains("[\"tower:type\"=\"observation\"]"))
        assertTrue(query.trimEnd().endsWith("out center tags;"))
    }

    @Test
    fun `search radius scales with route length within limits`() {
        assertEquals(2_000, OverpassPoiSource.searchRadiusMeters(5))
        assertEquals(17_500, OverpassPoiSource.searchRadiusMeters(50))
        assertEquals(50_000, OverpassPoiSource.searchRadiusMeters(150))
    }
}
