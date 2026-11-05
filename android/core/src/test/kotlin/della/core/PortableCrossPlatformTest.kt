// Cross-platform portable-session checks:
//  1. Re-create the F17 scenario entirely with the Kotlin core (normalise the same OSM input,
//     replay the same simulated drive through the Kotlin matcher, build the export) and
//     compare it with the web-produced reference package (only `exporter` may differ).
//  2. Write that Android-produced package to android/dist/F17_export_android.json so the
//     web implementation can import it.
//  3. Import the web reference package with the Kotlin core and re-export it losslessly.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.min

/** Port of web/scripts/fixture-lib.ts (local meters -> WGS84 around Yerevan test origin). */
object FixtureLib {
    val BASE = LonLat(44.5, 40.18)
    const val M_LAT = 110_574.0
    val M_LON = 111_320 * StrictMath.cos((40.18 * Math.PI) / 180)
    const val T0 = 1_700_000_000_000L

    fun ll(x: Double, y: Double) = LonLat(jsRound((BASE.lon + x / M_LON) * 1e7) / 1e7, jsRound((BASE.lat + y / M_LAT) * 1e7) / 1e7)

    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(ll(x0, y0), ll(x1, y0), ll(x1, y1), ll(x0, y1))

    class Osm {
        private val elements = ArrayList<JsonElement>()
        fun node(id: Long, x: Double, y: Double, tags: Map<String, String>? = null): Osm {
            val p = ll(x, y)
            val m = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("node"), "id" to JsonPrimitive(id), "lat" to ContractJson.num(p.lat), "lon" to ContractJson.num(p.lon))
            if (tags != null) m["tags"] = JsonObject(tags.mapValues { JsonPrimitive(it.value) })
            elements.add(JsonObject(m))
            return this
        }
        fun way(id: Long, nodes: List<Long>, tags: Map<String, String>): Osm {
            elements.add(ContractJson.obj("type" to JsonPrimitive("way"), "id" to JsonPrimitive(id), "nodes" to JsonArray(nodes.map { JsonPrimitive(it) }), "tags" to JsonObject(tags.mapValues { JsonPrimitive(it.value) })))
            return this
        }
        fun json(): JsonObject = ContractJson.obj("osm3s" to ContractJson.obj("timestamp_osm_base" to JsonPrimitive("fixture")), "elements" to JsonArray(elements))
    }

    /** Fixes along a local-meter polyline at constant speed, 1 Hz (no noise variant). */
    /** Port of mulberry32 in fixture-lib.ts (Int arithmetic == Math.imul semantics). */
    fun mulberry32(seed: Int): () -> Double {
        var a = seed
        return {
            a += 0x6d2b79f5
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
        }
    }

    fun track(points: List<Pair<Double, Double>>, idPrefix: String, speed: Double, accuracy: Double = 5.0, startT: Long = T0, intervalMs: Long = 1000, noise: Double = 0.0, seed: Int = 1): List<LocationObservation> {
        val rnd = mulberry32(seed)
        val step = (speed * intervalMs) / 1000
        class Seg(val a: Pair<Double, Double>, val b: Pair<Double, Double>, val len: Double)
        val segs = (1 until points.size).map { i ->
            val a = points[i - 1]
            val b = points[i]
            Seg(a, b, jsHypot(b.first - a.first, b.second - a.second))
        }
        val total = segs.sumOf { it.len }
        val out = ArrayList<LocationObservation>()
        var t = startT
        var d = 0.0
        var k = 0
        while (d <= total + 1e-9) {
            var rem = d
            var seg = segs[0]
            for (s in segs) {
                seg = s
                if (rem <= s.len) break
                rem -= s.len
            }
            val f = if (seg.len > 0) min(1.0, rem / seg.len) else 0.0
            val x = seg.a.first + (seg.b.first - seg.a.first) * f + (if (noise != 0.0) (rnd() * 2 - 1) * noise else 0.0)
            val y = seg.a.second + (seg.b.second - seg.a.second) * f + (if (noise != 0.0) (rnd() * 2 - 1) * noise else 0.0)
            val brg = ((atan2(seg.b.first - seg.a.first, seg.b.second - seg.a.second) * 180) / Math.PI + 360) % 360
            val p = ll(x, y)
            out.add(
                LocationObservation(
                    observationId = "$idPrefix$k", timestamp = t, monotonicMs = null, lon = p.lon, lat = p.lat,
                    horizontalAccuracyMeters = accuracy, speedMps = speed, speedAccuracyMps = null,
                    bearingDeg = if (speed < 0.5) null else jsRound(brg * 10) / 10, bearingAccuracyDeg = null,
                    altitudeMeters = null, altitudeAccuracyMeters = null, source = "simulated_test", ageMs = 0.0,
                ),
            )
            t += intervalMs
            d += step
            k++
        }
        return out
    }
}

class PortableCrossPlatformTest {
    private val distDir = File(System.getProperty("dist.dir") ?: "../dist")
    private val reference: JsonObject get() = Fixtures.load("F17_portable_roundtrip.json")

    private fun buildAndroidF17(): Pair<SessionExportPackage, GraphSnapshot> {
        val osm = FixtureLib.Osm().node(1, 0.0, 0.0).node(2, 300.0, 0.0).way(80, listOf(1, 2), mapOf("highway" to "residential")).json()
        val g = normalizeOverpass(OverpassResponse.parse(osm), NormalizeOptions(fetchedAt = "1970-01-01T00:00:00.000Z", dataState = "synthetic_test_fixture", providerId = "fixture"))
        val gi = GraphIndex(g)
        val zone = ZoneDefinition.Polygon(FixtureLib.rect(-50.0, -50.0, 350.0, 50.0))
        val req = computeRequirements(gi, zone)
        val sessionId = "sess-fixture-17"
        val events = ArrayList<SessionEvent>()
        var seq = 0L
        fun push(type: String, payload: JsonObject, ts: Long) {
            seq++
            events.add(SessionEvent("$sessionId:$seq", seq, sessionId, type, ts, SCHEMA_VERSION, payload))
        }
        val t0 = FixtureLib.T0
        push(EventType.SESSION_CREATED, JsonObject(emptyMap()), t0)
        push(EventType.START_CONFIRMED, JsonObject(emptyMap()), t0 + 1)
        var st = initialMatcherState()
        for (o in FixtureLib.track(listOf(0.0 to 0.0, 130.0 to 0.0), "x", 10.0, 5.0)) {
            val r = processObservation(gi, st, o, o.timestamp)
            st = r.state
            for (t in r.traversals) push(EventType.TRAVERSAL_CONFIRMED, ContractJson.obj("traversal" to ContractJson.encodeTraversal(t)), o.timestamp)
        }
        push(
            EventType.REQUIREMENT_EXCLUDED,
            ContractJson.obj("requirementIds" to ContractJson.strs(listOf("osm:w80:n1-n2:b#0")), "reason" to JsonPrimitive("blocked"), "note" to JsonPrimitive("construction")),
            t0 + 20_000,
        )
        push(EventType.PAUSED, JsonObject(emptyMap()), t0 + 21_000)
        val prog = applyEvents(emptyProgress(), events, ApplyContext(sessionId, g.contentHash, g.directedEdges.associate { it.edgeId to it.lengthMeters }))
        val session = sessionDocument(
            SessionBase(
                sessionId = sessionId, graphId = g.graphId, contentHash = g.contentHash,
                zone = Zone("zone-17", 1, zone), vehicleProfile = "passenger_car", boundaryPolicy = BoundaryPolicy.STRICT_INSIDE, returnToStart = false,
                startSelection = StartSelection(FixtureLib.ll(0.0, 0.0), SnappedStart("osm:w80:n1-n2:f", 0.0, FixtureLib.ll(0.0, 0.0), 0.0), true),
                createdAt = "1970-01-01T00:00:00.000Z",
            ),
            req.requirements, prog,
        )
        val pkg = buildExport(g, session, events, ExportOptions("android", "0.1.0", includeGraph = true, includeObservations = false, exportedAt = "1970-01-01T00:00:00.000Z"))
        return pkg to g
    }

    private fun withoutExporter(o: JsonElement): JsonObject = JsonObject((o as JsonObject).filterKeys { it != "exporter" })

    @Test
    fun androidProducedF17MatchesWebReferenceAndIsWrittenToDist() {
        val (pkg, g) = buildAndroidF17()
        val ref = reference["referencePackage"] as JsonObject
        val exp = reference["expectedAfterImport"] as JsonObject
        assertEquals((exp["contentHash"] as JsonPrimitive).content, g.contentHash, "graph hash identical to web")
        val mine = encodeExport(pkg)
        val diffs = close(withoutExporter(mine), withoutExporter(ref), 1e-9)
        assertEquals(emptyList<String>(), diffs.take(20), "Android export == web reference except exporter")
        assertEquals("android", ((mine["exporter"] as JsonObject)["platform"] as JsonPrimitive).content)
        // The Android package must itself validate and import.
        val text = ContractJson.prettyString(mine)
        assertTrue(SchemaValidator.contract.validateDef("sessionExportPackage", mine).ok)
        val imp = validateImport(text) { null }
        assertTrue(imp.ok, imp.errors.joinToString(","))
        distDir.mkdirs()
        File(distDir, "F17_export_android.json").writeText(text + "\n")
        println("WROTE ${File(distDir, "F17_export_android.json").absolutePath} sessionId=${pkg.session.sessionId} hash=${g.contentHash} events=${pkg.events.size}")
    }

    @Test
    fun webReferencePackageImportsAndReExportsLosslessly() {
        val ref = reference["referencePackage"] as JsonObject
        val raw = ContractJson.stringify(ref)
        val imp = validateImport(raw) { null }
        assertTrue(imp.ok, imp.errors.joinToString(","))
        val pkg = imp.pkg!!
        val g = imp.graph!!
        // Re-derive the session from the imported event log and re-export with the Android core.
        val prog = applyEvents(emptyProgress(), pkg.events, ApplyContext(pkg.session.sessionId, g.contentHash, g.directedEdges.associate { it.edgeId to it.lengthMeters }))
        val s = pkg.session
        val doc = sessionDocument(
            SessionBase(s.sessionId, s.graphId, s.contentHash, s.zone, s.vehicleProfile, s.boundaryPolicy, s.returnToStart, s.startSelection, s.createdAt),
            s.requiredIntervals, prog,
        )
        val re = buildExport(g, doc, pkg.events, ExportOptions("android", "0.1.0", includeGraph = true, includeObservations = false, exportedAt = pkg.exportedAt))
        val diffs = close(withoutExporter(encodeExport(re)), withoutExporter(ref), 1e-9)
        assertEquals(emptyList<String>(), diffs.take(20), "re-export identical (directions, partial intervals, exclusions, fingerprint)")
        // Opposite direction never aliased: backward requirement stays blocked, not covered.
        assertEquals("blocked", doc.requiredIntervals.first { it.edgeId.endsWith(":b") }.status)
        assertEquals("partial", doc.requiredIntervals.first { it.edgeId.endsWith(":f") }.status)
    }

    @Test
    fun graphlessExportNeedsMatchingGraphOnDevice() {
        val (pkg, g) = buildAndroidF17()
        val noGraph = pkg.copy(graph = null)
        val text = ContractJson.stringify(encodeExport(noGraph))
        assertEquals("graph_unavailable_on_this_device", validateImport(text) { null }.errors[0])
        assertTrue(validateImport(text) { h -> if (h == g.contentHash) g else null }.ok)
    }
}
