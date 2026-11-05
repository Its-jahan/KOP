package com.della.coveragenavigator

import della.core.ContractJson
import della.core.GeographicExtent
import della.core.LonLat
import della.core.OVERPASS_HIGHWAY_REGEX
import della.core.overpassQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonAndQueryTest {
    @Test
    fun overpassQueryHasContractShapeAndSouthWestNorthEastOrder() {
        val q = overpassQuery(GeographicExtent(west = 44.49, south = 40.17, east = 44.52, north = 40.19))
        assertTrue(q.startsWith("[out:json][timeout:90];way[\"highway\"~\"$OVERPASS_HIGHWAY_REGEX\"](40.1700000,44.4900000,40.1900000,44.5200000)->.w;"))
        assertTrue(q.endsWith("(.w;.w>;);out body;rel(bw.w)[\"type\"~\"^restriction\"];out body;"))
    }

    @Test
    fun zoneGeoJsonIsValidJsonWithClosedRing() {
        val ring = listOf(LonLat(44.5, 40.18), LonLat(44.51, 40.18), LonLat(44.51, 40.19))
        val fc = ContractJson.parse(GeoJson.zone(ring, ring, closed = true)) as JsonObject
        val features = fc["features"] as JsonArray
        assertEquals(4, features.size) // polygon + 3 vertices
        val poly = ((features[0] as JsonObject)["geometry"] as JsonObject)["coordinates"] as JsonArray
        val outer = poly[0] as JsonArray
        assertEquals(outer.first(), outer.last())
    }

    @Test
    fun emptyCollectionsParse() {
        assertTrue(ContractJson.parse(GeoJson.EMPTY) is JsonObject)
        assertTrue(ContractJson.parse(GeoJson.location(null, null, false, null)) is JsonObject)
        assertTrue(ContractJson.parse(GeoJson.markers(LonLat(1.0, 2.0), LonLat(1.0, 2.0), 90.0)) is JsonObject)
    }
}
