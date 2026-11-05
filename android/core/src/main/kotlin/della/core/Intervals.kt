// Closed-interval union arithmetic on directed edge offsets (meters). Port of intervals.ts.
package della.core

import kotlin.math.max
import kotlin.math.min

data class Interval(val a: Double, val b: Double)

/** Merge intervals, joining those that overlap or touch within `joinTol`. */
fun unionIntervals(list: List<Interval>, joinTol: Double = 1e-6): List<Interval> {
    val sorted = list.filter { it.b > it.a }
        .sortedWith { x, y -> if (x.a != y.a) x.a.compareTo(y.a) else x.b.compareTo(y.b) }
    val out = ArrayList<DoubleArray>()
    for (iv in sorted) {
        val last = out.lastOrNull()
        if (last != null && iv.a <= last[1] + joinTol) {
            if (iv.b > last[1]) last[1] = iv.b
        } else out.add(doubleArrayOf(iv.a, iv.b))
    }
    return out.map { Interval(it[0], it[1]) }
}

fun addInterval(list: List<Interval>, iv: Interval): List<Interval> = unionIntervals(list + iv)

fun totalLength(list: List<Interval>): Double {
    var s = 0.0
    for (iv in list) s += iv.b - iv.a
    return s
}

/** Length of the overlap between a union and [a,b]. */
fun coveredWithin(union: List<Interval>, a: Double, b: Double): Double {
    var s = 0.0
    for (iv in union) {
        val lo = max(iv.a, a)
        val hi = min(iv.b, b)
        if (hi > lo) s += hi - lo
    }
    return s
}

/** Parts of [a,b] not covered by union. */
fun uncoveredParts(union: List<Interval>, a: Double, b: Double): List<Interval> {
    val out = ArrayList<Interval>()
    var cursor = a
    for (iv in union) {
        if (iv.b <= cursor) continue
        if (iv.a >= b) break
        if (iv.a > cursor) out.add(Interval(cursor, min(iv.a, b)))
        cursor = max(cursor, iv.b)
        if (cursor >= b) break
    }
    if (cursor < b) out.add(Interval(cursor, b))
    return out
}
