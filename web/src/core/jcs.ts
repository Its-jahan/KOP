// RFC 8785 JSON Canonicalization Scheme, restricted profile used by the contract:
// numbers in hashed documents MUST be safe integers (coordinates are E7 integers,
// lengths are integer millimetres). Non-integers are rejected so both platforms
// produce identical bytes without relying on float formatting.

export function canonicalize(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) {
      throw new Error(`canonicalize: non-integer or unsafe number ${value}`);
    }
    return Object.is(value, -0) ? '0' : String(value);
  }
  if (typeof value === 'string') return quoteString(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalize).join(',') + ']';
  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const keys = Object.keys(obj)
      .filter((k) => obj[k] !== undefined)
      .sort(compareUtf16);
    return '{' + keys.map((k) => quoteString(k) + ':' + canonicalize(obj[k])).join(',') + '}';
  }
  throw new Error(`canonicalize: unsupported type ${typeof value}`);
}

function compareUtf16(a: string, b: string): number {
  // JS string comparison is by UTF-16 code units, as RFC 8785 requires.
  return a < b ? -1 : a > b ? 1 : 0;
}

/** ECMAScript JSON.stringify string escaping (well-formed variant). */
export function quoteString(s: string): string {
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 0x22) out += '\\"';
    else if (c === 0x5c) out += '\\\\';
    else if (c === 0x08) out += '\\b';
    else if (c === 0x0c) out += '\\f';
    else if (c === 0x0a) out += '\\n';
    else if (c === 0x0d) out += '\\r';
    else if (c === 0x09) out += '\\t';
    else if (c < 0x20) out += '\\u' + c.toString(16).padStart(4, '0');
    else if (c >= 0xd800 && c <= 0xdbff) {
      const n = i + 1 < s.length ? s.charCodeAt(i + 1) : 0;
      if (n >= 0xdc00 && n <= 0xdfff) {
        out += s[i] + s[i + 1];
        i++;
      } else out += '\\u' + c.toString(16);
    } else if (c >= 0xdc00 && c <= 0xdfff) out += '\\u' + c.toString(16);
    else out += s[i];
  }
  return out + '"';
}

export async function sha256Hex(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}
