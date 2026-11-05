// Contract 1.0.0 conformance: same fixtures, same comparisons and same semantic
// assertions as web/tests/unit/conformance.test.ts.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

object Fixtures {
    val contractDir: File = File(System.getProperty("contract.dir") ?: "../../contract")
    val fixtureDir = File(contractDir, "fixtures")
    fun files(): List<File> = fixtureDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    fun load(name: String): JsonObject = ContractJson.parse(File(fixtureDir, name).readText()) as JsonObject
}

/** Deep compare with numeric tolerance (port of `close` in conformance.test.ts). Missing key != null. */
fun close(actual: JsonElement?, expected: JsonElement?, tol: Double, path: String = ""): List<String> {
    fun isNum(e: JsonElement?) = e is JsonPrimitive && e !is JsonNull && !e.isString && e.content != "true" && e.content != "false"
    if (isNum(expected) && isNum(actual)) {
        val a = (actual as JsonPrimitive).doubleOrNull!!
        val e = (expected as JsonPrimitive).doubleOrNull!!
        return if (Math.abs(a - e) <= tol) emptyList() else listOf("$path: $a != $e")
    }
    if (expected is JsonArray) {
        if (actual !is JsonArray || actual.size != expected.size) return listOf("$path: array length ${(actual as? JsonArray)?.size ?: "n/a"} != ${expected.size}")
        return expected.indices.flatMap { close(actual[it], expected[it], tol, "$path[$it]") }
    }
    if (expected is JsonObject) {
        if (actual !is JsonObject) return listOf("$path: not object")
        val keys = LinkedHashSet<String>().apply { addAll(expected.keys); addAll(actual.keys) }
        return keys.flatMap { close(actual[it], expected[it], tol, "$path.$it") }
    }
    // primitives (string/bool/null) and missing values: strict equality; missing (undefined) != null
    if (actual == null && expected == null) return emptyList()
    if (actual == null || expected == null) return listOf("$path: $actual != $expected")
    return if (actual == expected) emptyList() else listOf("$path: $actual != $expected")
}

private fun JsonObject.str(k: String) = (this[k] as JsonPrimitive).content
private fun JsonObject.dbl(k: String) = (this[k] as JsonPrimitive).doubleOrNull!!
private fun JsonElement?.strList(): List<String> = (this as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()

private fun coveredOf(track: JsonObject, edgeId: String): Double =
    ((track["unionByEdge"] as JsonObject)[edgeId] as? JsonArray)?.sumOf { iv ->
        val a = iv as JsonArray
        (a[1] as JsonPrimitive).doubleOrNull!! - (a[0] as JsonPrimitive).doubleOrNull!!
    } ?: 0.0

private fun containsSeq(seq: List<String>, sub: List<String>): Boolean {
    var i = 0
    while (i + sub.size <= seq.size) {
        if (sub.indices.all { seq[i + it] == sub[it] }) return true
        i++
    }
    return false
}

fun checkAssertion(a: JsonObject, out: JsonObject) {
    val plan = (a["policy"] as? JsonPrimitive)?.let { (out["plans"] as JsonObject)[it.content] as JsonObject }
    val tr = (a["track"] as? JsonPrimitive)?.let { (out["tracks"] as JsonObject)[it.content] as JsonObject }
    val graph = out["graph"] as JsonObject
    val reqs = out["requirements"] as JsonArray
    val paths = out["paths"] as JsonObject
    val kind = a.str("kind")
    val msg = "assertion $a"
    when (kind) {
        "plan_covers_all" -> {
            assertEquals(emptyList<String>(), plan!!["unreachable"].strList(), msg)
            assertEquals(reqs.size, plan["coveredRequirementIds"].strList().size, msg)
        }
        "plan_valid" -> assertEquals(JsonPrimitive(true), plan!!["valid"], msg)
        "plan_status" -> assertEquals(a.str("status"), plan!!.str("status"), msg)
        "plan_repeat_positive" -> assertTrue((plan!!["totals"] as JsonObject).dbl("repeatMeters") > 0, msg)
        "plan_has_no_turnaround" -> assertEquals(emptyList<String>(), plan!!["turnarounds"].strList(), msg)
        "plan_has_unverified_turnaround" -> assertTrue(plan!!["turnarounds"].strList().any { it.endsWith(":unverified") }, msg)
        "plan_turnaround_verified_at_node" -> {
            val idx = plan!!["turnarounds"].strList().filter { it.endsWith(":verified") }.map { it.split(":")[0].toInt() }
            val nodeNum = a.str("nodeId").replace("osm:", "")
            val seq = plan["edgeSequence"].strList()
            assertTrue(idx.any { seq[it].contains("$nodeNum-") || seq[it].contains("-$nodeNum:") }, msg)
        }
        "plan_not_contains_sequence" -> assertFalse(containsSeq(plan!!["edgeSequence"].strList(), a["sequence"].strList()), msg)
        "plan_never_uses_edge_prefix" -> assertFalse(plan!!["edgeSequence"].strList().any { it.startsWith(a.str("prefix")) }, msg)
        "plan_outside_at_least" -> assertTrue((plan!!["totals"] as JsonObject).dbl("outsideMeters") >= a.dbl("meters"), msg)
        "plan_outside_at_most" -> assertTrue((plan!!["totals"] as JsonObject).dbl("outsideMeters") <= a.dbl("meters"), msg)
        "unreachable_cause" -> assertTrue(plan!!["unreachable"].strList().contains("${a.str("requirementId")}:${a.str("cause")}"), msg)
        "unreachable_cause_present" -> assertTrue(plan!!["unreachable"].strList().any { it.endsWith(":${a.str("cause")}") }, msg)
        "edge_count" -> assertEquals(a.dbl("count"), graph.dbl("edgeCount"), msg)
        "node_count" -> assertEquals(a.dbl("count"), graph.dbl("nodeCount"), msg)
        "requirement_count" -> assertEquals(a.dbl("count").toInt(), reqs.size, msg)
        "requirement_interval" -> {
            val r = reqs.map { it as JsonObject }.first { it.str("requirementId") == a.str("requirementId") }
            assertTrue(Math.abs(r.dbl("start") - a.dbl("start")) < 1.0, msg)
            assertTrue(Math.abs(r.dbl("end") - a.dbl("end")) < 1.0, msg)
        }
        "no_edge_with_suffix" -> assertFalse(graph["edgeIds"].strList().any { it.endsWith(a.str("suffix")) }, msg)
        "path_not_equal" -> {
            val p = paths[a.str("path")]
            assertNotEquals(a["forbidden"], p, msg)
            assertTrue(p != null && p !is JsonNull, msg)
        }
        "path_not_contains_sequence" -> {
            val p = paths[a.str("path")]
            assertTrue(p != null && p !is JsonNull, msg)
            assertFalse(containsSeq(p.strList(), a["sequence"].strList()), msg)
        }
        "edge_covered_at_least" -> assertTrue(coveredOf(tr!!, a.str("edgeId")) >= a.dbl("meters"), msg)
        "edge_covered_at_most" -> assertTrue(coveredOf(tr!!, a.str("edgeId")) <= a.dbl("meters"), msg)
        "edge_covered_between" -> {
            val c = coveredOf(tr!!, a.str("edgeId"))
            assertTrue(c >= a.dbl("min") && c <= a.dbl("max"), "$msg covered=$c")
        }
        "edge_not_covered_in" -> {
            val ivs = ((tr!!["unionByEdge"] as JsonObject)[a.str("edgeId")] as? JsonArray) ?: JsonArray(emptyList())
            for (iv in ivs) {
                val s = ((iv as JsonArray)[0] as JsonPrimitive).doubleOrNull!!
                val e = (iv[1] as JsonPrimitive).doubleOrNull!!
                assertTrue(e <= a.dbl("start") || s >= a.dbl("end"), "interval [$s,$e] overlaps forbidden [${a.dbl("start")},${a.dbl("end")}]")
            }
        }
        "track_total_at_most" -> {
            val total = (tr!!["unionByEdge"] as JsonObject).keys.sumOf { coveredOf(tr, it) }
            assertTrue(total <= a.dbl("meters"), msg)
        }
        "track_only_edges" -> assertEquals(a["edgeIds"].strList().sorted(), (tr!!["unionByEdge"] as JsonObject).keys.sorted(), msg)
        "ignored_count_at_least" -> assertTrue(tr!!["ignored"].strList().size >= a.dbl("count"), msg)
        "gap_reasons_include" -> for (r in a["reasons"].strList()) assertTrue(tr!!["gaps"].strList().any { it.endsWith(":$r") }, "$msg reason $r")
        "deviation_detected" -> assertTrue(tr!!["deviationAt"] !is JsonNull, msg)
        "replan_valid" -> assertEquals(JsonPrimitive(true), (tr!!["replan"] as JsonObject)["valid"], msg)
        "replan_does_not_claim" -> assertFalse((tr!!["replan"] as JsonObject)["coveredRequirementIds"].strList().contains(a.str("requirementId")), msg)
        "eligibility" -> {
            assertEquals(a.str("value"), ((graph["eligibility"] as JsonObject)[a.str("edgeId")] as JsonPrimitive).content, msg)
            if (a["reason"] != null && a["reason"] !is JsonNull) {
                assertEquals(a.str("reason"), ((graph["eligibilityReason"] as JsonObject)[a.str("edgeId")] as JsonPrimitive).content, msg)
            }
        }
        "turn_lanes" -> assertEquals(
            (a["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            ((graph["turnLanes"] as JsonObject)[a.str("edgeId")] as? JsonPrimitive)?.content, msg,
        )
        "node_passage" -> assertEquals(a.str("value"), ((graph["nodePassage"] as JsonObject)[a.str("nodeId")] as? JsonPrimitive)?.content, msg)
        else -> throw AssertionError("unknown assertion $kind")
    }
}

/** Apply JSON-pointer `replace` patches like the reference test. */
fun applyPatch(doc: JsonElement, patches: JsonArray): JsonElement {
    var d = doc
    for (op in patches) {
        val o = op as JsonObject
        val parts = o.str("path").split("/").drop(1)
        d = replaceAt(d, parts, o["value"]!!)
    }
    return d
}

private fun replaceAt(d: JsonElement, parts: List<String>, value: JsonElement): JsonElement {
    if (parts.isEmpty()) return value
    val head = parts[0]
    return when (d) {
        is JsonObject -> JsonObject(LinkedHashMap(d).apply { put(head, replaceAt(d[head] ?: JsonNull, parts.drop(1), value)) })
        is JsonArray -> {
            val i = head.toInt()
            JsonArray(d.mapIndexed { k, e -> if (k == i) replaceAt(e, parts.drop(1), value) else e })
        }
        else -> throw IllegalArgumentException("cannot patch into primitive")
    }
}

class ConformanceTest {
    private fun runGraphFixture(doc: JsonObject) {
        val out = Conformance.runFixture(doc["input"] as JsonObject)
        val tol = (doc["tolerances"] as JsonObject).dbl("offsetMeters")
        val diffs = close(out, doc["expected"], tol)
        assertEquals(emptyList<String>(), diffs.take(15), "diffs vs expected (first 15)")
        for (a in doc["assertions"] as JsonArray) checkAssertion(a as JsonObject, out)
        for ((_, pl) in out["plans"] as JsonObject) assertEquals(JsonPrimitive(true), (pl as JsonObject)["valid"], "plan valid")
    }

    private fun runDecidePlan(doc: JsonObject) {
        val cases = doc["cases"] as JsonArray
        for (c in cases) {
            val co = c as JsonObject
            val ctx = co["ctx"] as JsonObject
            val r = co["result"] as JsonObject
            val d = decidePlan(
                PlanningContext(ctx.str("graphHash"), ctx.dbl("zoneVersion").toInt(), ctx.dbl("latestRequestedPlanningRevision").toLong(), ctx.dbl("acceptedPlanningRevision").toLong(), ctx.dbl("progressRevision").toLong()),
                PlanResultMeta(r.str("graphHash"), r.dbl("zoneVersion").toInt(), r.dbl("planningRevision").toLong(), r.dbl("basedOnProgressRevision").toLong(), r.str("status")),
            )
            val actual = if (d.accept) ContractJson.obj("accept" to JsonPrimitive(true), "reconcile" to JsonPrimitive(d.reconcile!!))
            else ContractJson.obj("accept" to JsonPrimitive(false), "reason" to JsonPrimitive(d.reason!!))
            assertEquals(co["expected"], actual, co.str("name"))
        }
        val c = cases.map { it as JsonObject }.first { it.str("name") == "arrives_after_newer_accepted" }
        assertEquals(JsonPrimitive(false), (c["expected"] as JsonObject)["accept"])
    }

    private fun runRecovery(doc: JsonObject) {
        for (c in doc["cases"] as JsonArray) {
            val co = c as JsonObject
            val r = recoverEventLog(co.str("sessionId"), StorageImage(null, co["records"].strList()))
            val actual = ContractJson.obj(
                "eventIds" to ContractJson.strs(r.events.map { it.eventId }),
                "lastValidSeq" to JsonPrimitive(r.lastValidSeq),
                "discarded" to JsonArray(r.discarded.map { ContractJson.obj("index" to JsonPrimitive(it.index), "reason" to JsonPrimitive(it.reason)) }),
            )
            assertEquals(emptyList<String>(), close(actual, co["expected"], 0.0), co.str("name"))
        }
    }

    private fun runMapFailure(doc: JsonObject) {
        for (c in doc["cases"] as JsonArray) {
            val co = c as JsonObject
            val o = mapFailure(FailureInput.fromJson(co["input"] as JsonObject))
            val actual = ContractJson.obj(
                "canStartNavigation" to JsonPrimitive(o.canStartNavigation),
                "gpsState" to ContractJson.str(o.gpsState),
                "dataState" to ContractJson.str(o.dataState),
                "code" to JsonPrimitive(o.code),
            )
            assertEquals(emptyList<String>(), close(actual, co["expected"], 0.0), co.toString())
            assertEquals(JsonPrimitive(false), (co["expected"] as JsonObject)["canStartNavigation"])
        }
    }

    private fun runPortable(doc: JsonObject) {
        val ref = doc["referencePackage"]!!
        val raw = ContractJson.stringify(ref)
        assertTrue(SchemaValidator.contract.validateDef("sessionExportPackage", ref).ok, "reference package schema-valid")
        val r = validateImport(raw) { null }
        assertTrue(r.ok, r.errors.joinToString(","))
        val pkg = r.pkg!!
        val g = r.graph!!
        val exp = doc["expectedAfterImport"] as JsonObject
        assertEquals(exp.str("contentHash"), g.contentHash)
        val prog = applyEvents(emptyProgress(), pkg.events, ApplyContext(pkg.session.sessionId, g.contentHash, g.directedEdges.associate { it.edgeId to it.lengthMeters }))
        val fwd = JsonArray((prog.union["osm:w80:n1-n2:f"] ?: emptyList()).map { JsonArray(listOf(ContractJson.num(jsRound(it.a * 100) / 100), ContractJson.num(jsRound(it.b * 100) / 100))) })
        assertEquals(emptyList<String>(), close(fwd, exp["forwardUnion"], 0.5))
        assertEquals(emptyList<Interval>(), prog.union["osm:w80:n1-n2:b"] ?: emptyList<Interval>())
        assertEquals(exp["activeExclusions"].strList(), prog.exclusions.values.filter { it.active }.map { it.requirementId })
        assertEquals(exp.str("status"), prog.status)
        val (_, statuses) = computeMetrics(pkg.session.requiredIntervals, prog)
        assertEquals(exp["requirementStatuses"], JsonObject(statuses.mapValues { JsonPrimitive(it.value) }))
        for (c in doc["invalidCases"] as JsonArray) {
            val co = c as JsonObject
            val text = if (co["raw"] != null) co.str("raw") else ContractJson.stringify(applyPatch(ContractJson.parse(raw), co["patch"] as JsonArray))
            val res = validateImport(text) { null }
            assertFalse(res.ok, co.str("name"))
            assertEquals(co.str("expectedFirstError"), res.errors[0], co.str("name"))
        }
    }

    @TestFactory
    fun contractFixtures(): List<DynamicTest> {
        val files = Fixtures.files()
        assertTrue(files.size >= 17, "fixtures found in ${Fixtures.fixtureDir}")
        return files.map { f ->
            DynamicTest.dynamicTest(f.nameWithoutExtension) {
                val doc = ContractJson.parse(f.readText()) as JsonObject
                val t0 = System.nanoTime()
                when (doc.str("kind")) {
                    "graph" -> runGraphFixture(doc)
                    "decidePlan" -> runDecidePlan(doc)
                    "recoverEventLog" -> runRecovery(doc)
                    "mapFailure" -> runMapFailure(doc)
                    "portable" -> runPortable(doc)
                    else -> throw AssertionError("unknown fixture kind")
                }
                println("CONFORMANCE PASS ${doc.str("fixtureId")} (${doc.str("kind")}) in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
    }

    @TestFactory
    fun jcsVectors(): List<DynamicTest> {
        val vectors = ContractJson.parse(File(Fixtures.contractDir, "vectors/jcs-vectors.json").readText()) as JsonArray
        return vectors.mapIndexed { i, v ->
            DynamicTest.dynamicTest("vector $i") {
                val o = v as JsonObject
                assertEquals(o.str("canonical"), canonicalize(o["input"]!!))
                assertEquals(o.str("sha256"), sha256Hex(o.str("canonical")))
            }
        }
    }

    @Test
    fun rejectsNonIntegerNumbers() {
        assertThrows(CanonicalizationException::class.java) { canonicalize(ContractJson.parse("{\"a\":1.5}")) }
    }

    @Test
    fun graphHashesMatchExactly() {
        // Explicit, separately reported check of every graph fixture's contentHash.
        for (f in Fixtures.files()) {
            val doc = ContractJson.parse(f.readText()) as JsonObject
            if (doc.str("kind") != "graph") continue
            val input = doc["input"] as JsonObject
            val g = normalizeOverpass(OverpassResponse.parse(input["osm"] as JsonObject), NormalizeOptions(fetchedAt = "1970-01-01T00:00:00.000Z", dataState = "synthetic_test_fixture", providerId = "fixture"))
            val expected = ((doc["expected"] as JsonObject)["graph"] as JsonObject).str("contentHash")
            assertEquals(expected, g.contentHash, f.name)
            assertTrue(verifyGraphHash(g))
            println("HASH OK ${f.nameWithoutExtension} ${g.contentHash}")
        }
    }

    @Test
    fun roundTripGraphJsonKeepsHash() {
        val doc = Fixtures.load("F04_forbidden_turn_via_way.json")
        val g = normalizeOverpass(OverpassResponse.parse((doc["input"] as JsonObject)["osm"] as JsonObject), NormalizeOptions(fetchedAt = "x", providerId = "fixture"))
        val back = ContractJson.decodeGraph(ContractJson.parse(ContractJson.stringify(ContractJson.encodeGraph(g))))
        assertEquals(g.contentHash, computeContentHash(back))
        assertTrue(SchemaValidator.contract.validateDef("graphSnapshot", ContractJson.encodeGraph(g)).ok)
        assertNotNull(back)
    }
}
