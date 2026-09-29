/**
 * The webOS MD5, held against Node's own. Run with `node webos/test/md5.test.js`.
 *
 * It exists for Subsonic token auth (t = md5(password + salt)), where a wrong digest
 * is a login that fails with "wrong username or password" and nothing else to go on.
 */
'use strict';

const assert = require('assert');
const crypto = require('crypto');
const md5 = require('../js/md5.js');

const ref = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex');

const cases = [
    '',
    'a',
    'abc',
    'message digest',
    'sesame' + 'c19b2d',                 // the Subsonic API documentation's own example
    'x'.repeat(55), 'x'.repeat(56), 'x'.repeat(63), 'x'.repeat(64), 'x'.repeat(1000),
    'pässwörd—✓' + 'deadbeef',          // UTF-8, as a password can be
];
let ok = 0;
for (const c of cases) {
    assert.strictEqual(md5(c), ref(c), `md5(${JSON.stringify(c.slice(0, 20))})`);
    ok++;
}
// The Subsonic docs: password "sesame", salt "c19b2d" -> token 26719a1196d2a940705a59634eb18eab.
assert.strictEqual(md5('sesamec19b2d'), '26719a1196d2a940705a59634eb18eab');
console.log(`  ok   ${ok} digests match Node's crypto, and the Subsonic docs' example token`);
console.log('\nAll checks passed.');
