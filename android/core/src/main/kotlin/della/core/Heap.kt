// Binary min-heap (port of heap.ts).
package della.core

class MinHeap<T>(private val less: (T, T) -> Boolean) {
    private val a = ArrayList<T>()
    val size: Int get() = a.size

    fun push(v: T) {
        a.add(v)
        var i = a.size - 1
        while (i > 0) {
            val p = (i - 1) shr 1
            if (!less(a[i], a[p])) break
            val t = a[i]; a[i] = a[p]; a[p] = t
            i = p
        }
    }

    fun pop(): T? {
        if (a.isEmpty()) return null
        val top = a[0]
        val last = a.removeAt(a.size - 1)
        if (a.isNotEmpty()) {
            a[0] = last
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var m = i
                if (l < a.size && less(a[l], a[m])) m = l
                if (r < a.size && less(a[r], a[m])) m = r
                if (m == i) break
                val t = a[i]; a[i] = a[m]; a[m] = t
                i = m
            }
        }
        return top
    }
}
