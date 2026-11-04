// Closed-interval union arithmetic on directed edge offsets (meters).

export type Interval = [number, number];

/** Merge intervals, joining those that overlap or touch within `joinTol`. */
export function unionIntervals(list: Interval[], joinTol = 1e-6): Interval[] {
  const sorted = list
    .filter(([a, b]) => b > a)
    .map(([a, b]) => [a, b] as Interval)
    .sort((x, y) => x[0] - y[0] || x[1] - y[1]);
  const out: Interval[] = [];
  for (const iv of sorted) {
    const last = out[out.length - 1];
    if (last && iv[0] <= last[1] + joinTol) {
      if (iv[1] > last[1]) last[1] = iv[1];
    } else out.push([iv[0], iv[1]]);
  }
  return out;
}

export function addInterval(list: Interval[], iv: Interval): Interval[] {
  return unionIntervals([...list, iv]);
}

export function totalLength(list: Interval[]): number {
  return list.reduce((s, [a, b]) => s + (b - a), 0);
}

/** Length of the overlap between a union and [a,b]. */
export function coveredWithin(union: Interval[], a: number, b: number): number {
  let s = 0;
  for (const [x, y] of union) {
    const lo = Math.max(x, a);
    const hi = Math.min(y, b);
    if (hi > lo) s += hi - lo;
  }
  return s;
}

/** Parts of [a,b] not covered by union. */
export function uncoveredParts(union: Interval[], a: number, b: number): Interval[] {
  const out: Interval[] = [];
  let cursor = a;
  for (const [x, y] of union) {
    if (y <= cursor) continue;
    if (x >= b) break;
    if (x > cursor) out.push([cursor, Math.min(x, b)]);
    cursor = Math.max(cursor, y);
    if (cursor >= b) break;
  }
  if (cursor < b) out.push([cursor, b]);
  return out;
}
