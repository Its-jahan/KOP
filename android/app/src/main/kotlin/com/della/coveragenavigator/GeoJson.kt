// GeoJSON builders for the map layers (strings, built off the UI thread).
package com.della.coveragenavigator

import della.core.GraphIndex
import della.core.Interval
import della.core.LonLat
import della.core.RequiredInterval
import della.core.RoutePlan
import della.core.quoteString
import della.core.slicePolyline
import java.util.Locale

object GeoJson {
    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    private fun StringBuilder.coord(p: LonLat): StringBuilder {
        append('[').append(String.format(Locale.ROOT, "%.7f", p.lon)).append(',').append(String.format(Locale.ROOT, "%.7f", p.lat)).append(']')
        return this
    }

    private fun StringBuilder.line(coords: List<LonLat>): StringBuilder {
        append("{\"type\":\"LineString\",\"coordinates\":[")
        coords.forEachIndexed { i, p ->
            if (i > 0) append(',')
            coord(p)
        }
        append("]}")
        return this
    }

    private class FC {
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        var n = 0
        fun add(geometry: StringBuilder.() -> Unit, props: Map<String, Any?>) {
            if (n++ > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":")
            sb.geometry()
            sb.append(",\"properties\":{")
            var k = 0
            for ((key, v) in props) {
                if (k++ > 0) sb.append(',')
                sb.append(quoteString(key)).append(':')
                when (v) {
                    null -> sb.append("null")
                    is Number -> sb.append(String.format(Locale.ROOT, "%.3f", v.toDouble()))
                    is Boolean -> sb.append(v)
                    else -> sb.append(quoteString(v.toString()))
                }
            }
            sb.append("}}")
        }
        fun done(): String = sb.append("]}").toString()
    }

    fun zone(ring: List<LonLat>, vertices: List<LonLat>, closed: Boolean): String {
        val fc = FC()
        if (closed && ring.size >= 3) {
            fc.add({
                append("{\"type\":\"Polygon\",\"coordinates\":[[")
                (ring + ring[0]).forEachIndexed { i, p ->
                    if (i > 0) append(',')
                    coord(p)
                }
                append("]]}")
            }, mapOf("kind" to "zone"))
        } else if (ring.size >= 2) {
            fc.add({ line(ring) }, mapOf("kind" to "draft"))
        }
        vertices.forEachIndexed { i, p ->
            fc.add({
                append("{\"type\":\"Point\",\"coordinates\":")
                coord(p)
                append('}')
            }, mapOf("kind" to "vertex", "index" to i))
        }
        return fc.done()
    }

    /** One feature per required directed interval, coloured by status (direction kept in geometry order). */
    fun coverage(gi: GraphIndex, requirements: List<RequiredInterval>, statuses: Map<String, String>): String {
        val fc = FC()
        for (r in requirements) {
            val i = gi.idx[r.edgeId] ?: continue
            val geo = slicePolyline(gi.edges[i].geometry, gi.cum[i], r.startOffsetMeters, r.endOffsetMeters)
            fc.add({ line(geo) }, mapOf("status" to (statuses[r.requirementId] ?: r.status)))
        }
        return fc.done()
    }

    /** Confirmed (observed) directed intervals. */
    fun visited(gi: GraphIndex, union: Map<String, List<Interval>>): String {
        val fc = FC()
        for ((edgeId, ivs) in union) {
            val i = gi.idx[edgeId] ?: continue
            for (iv in ivs) {
                if (iv.b - iv.a < 0.5) continue
                fc.add({ line(slicePolyline(gi.edges[i].geometry, gi.cum[i], iv.a, iv.b)) }, mapOf("status" to "covered"))
            }
        }
        return fc.done()
    }

    fun plan(gi: GraphIndex, plan: RoutePlan?, fromStep: Int = 0): String {
        if (plan == null) return EMPTY
        val fc = FC()
        for (k in fromStep until plan.steps.size) {
            val s = plan.steps[k]
            val i = gi.idx[s.edgeId] ?: continue
            if (s.toOffsetMeters - s.fromOffsetMeters <= 0.01) continue
            fc.add({ line(slicePolyline(gi.edges[i].geometry, gi.cum[i], s.fromOffsetMeters, s.toOffsetMeters)) }, mapOf("kind" to s.kind, "step" to k))
        }
        return fc.done()
    }

    fun markers(requested: LonLat?, snapped: LonLat?, snappedBearing: Double?): String {
        val fc = FC()
        requested?.let { p -> fc.add({ append("{\"type\":\"Point\",\"coordinates\":"); coord(p); append('}') }, mapOf("kind" to "requested")) }
        snapped?.let { p -> fc.add({ append("{\"type\":\"Point\",\"coordinates\":"); coord(p); append('}') }, mapOf("kind" to "snapped", "bearing" to (snappedBearing ?: 0.0))) }
        return fc.done()
    }

    fun location(p: LonLat?, accuracy: Double?, reliable: Boolean, bearing: Double?): String {
        if (p == null) return EMPTY
        val fc = FC()
        fc.add({ append("{\"type\":\"Point\",\"coordinates\":"); coord(p); append('}') }, mapOf("accuracy" to (accuracy ?: 0.0), "reliable" to reliable, "bearing" to (bearing ?: -1.0)))
        return fc.done()
    }
}
