/**
 * MD5 of a UTF-8 string, as lowercase hex — for Subsonic token auth and nothing else.
 *
 * Subsonic's safe login is `t = md5(password + salt)` with a fresh random `s`, so the
 * password itself never appears in a URL. The browser's Web Crypto has no MD5, which
 * is why this app used to send `p=<password>` in the query string of every request —
 * every stream URL, cover URL and API call carrying the password in the clear, into
 * any log or proxy on the way. MD5 is not being used for its strength here; it is the
 * primitive the Subsonic API specifies.
 *
 * The RFC 1321 algorithm, dependency-free like the rest of the webOS app. Checked
 * against Node's own crypto in webos/test/md5.test.js.
 */
(function (global) {
    'use strict';

    function utf8Bytes(str) {
        return new TextEncoder().encode(str);
    }

    const S = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ];
    const K = new Uint32Array(64);
    for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 0x100000000) >>> 0;

    function md5(str) {
        const msg = utf8Bytes(str);
        const bitLen = msg.length * 8;
        // Pad: 0x80, zeros to 56 mod 64, then the 64-bit little-endian length.
        const padded = new Uint8Array(((msg.length + 8) >> 6) * 64 + 64);
        padded.set(msg);
        padded[msg.length] = 0x80;
        const view = new DataView(padded.buffer);
        view.setUint32(padded.length - 8, bitLen >>> 0, true);
        view.setUint32(padded.length - 4, Math.floor(bitLen / 0x100000000), true);

        let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
        const M = new Uint32Array(16);
        for (let off = 0; off < padded.length; off += 64) {
            for (let i = 0; i < 16; i++) M[i] = view.getUint32(off + i * 4, true);
            let A = a0, B = b0, C = c0, D = d0;
            for (let i = 0; i < 64; i++) {
                let F, g;
                if (i < 16) { F = (B & C) | (~B & D); g = i; }
                else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
                else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
                else { F = C ^ (B | ~D); g = (7 * i) % 16; }
                F = (F + A + K[i] + M[g]) >>> 0;
                A = D; D = C; C = B;
                B = (B + ((F << S[i]) | (F >>> (32 - S[i])))) >>> 0;
            }
            a0 = (a0 + A) >>> 0; b0 = (b0 + B) >>> 0; c0 = (c0 + C) >>> 0; d0 = (d0 + D) >>> 0;
        }
        const out = new DataView(new ArrayBuffer(16));
        out.setUint32(0, a0, true); out.setUint32(4, b0, true);
        out.setUint32(8, c0, true); out.setUint32(12, d0, true);
        let hex = '';
        for (let i = 0; i < 16; i++) hex += out.getUint8(i).toString(16).padStart(2, '0');
        return hex;
    }

    global.CAMusicMd5 = md5;
    if (typeof module !== 'undefined' && module.exports) module.exports = md5;
})(typeof window !== 'undefined' ? window : globalThis);
