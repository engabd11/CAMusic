/**
 * CAMusic webOS TV — the light show.
 *
 * Turns the television itself into the light source. For anyone without Hue lamps
 * this is the whole feature: in a dark room a 55-inch panel is a very large,
 * very controllable wash, and the app was already the thing that knows what the
 * music is doing.
 *
 * Two surfaces, both driven by one engine:
 *   - an **ambient** layer behind the ordinary UI, subtle enough to read over;
 *   - a **full-screen** mode with nothing else on it, for the dark-room case.
 *
 * ## Where the signal comes from
 *
 * A Web Audio `AnalyserNode` tapped off the single `<audio>` element, when the TV
 * allows it. That "when" is doing real work: webOS is explicit that an app gets one
 * audio element because there is one hardware decoder, and routing that element
 * through a Web Audio graph is exactly the kind of thing a TV may refuse, mute, or
 * silently downgrade. So the tap is feature-detected, fenced in try/catch, and
 * *verified* — if it yields nothing but silence while the element is plainly
 * playing, it is abandoned and the show falls back to a time-based engine.
 *
 * **The music is never at risk.** The fallback looks good on its own; a failed tap
 * costs the reactivity and nothing else.
 *
 * ## Why it is not simply "bright colours"
 *
 * The brief was a dark room, and the constraint that follows is not negotiable:
 * saturated red and yellow at high brightness hurt to look at, and a large panel at
 * 2am is not a phone screen. Three rules, applied to every colour before it reaches
 * the canvas — see `comfort()`:
 *
 *   1. **Clamp relative luminance, not HSV value.** Yellow at V=1 is about ten times
 *      the luminance of blue at V=1. Clamping V treats them as equal and lets the
 *      yellow through at full power, which is precisely the colour that hurts.
 *   2. **Attenuate the warm arc further.** Hues from red to yellow get an extra
 *      reduction on top, scaled by saturation.
 *   3. **Rate-limit brightness in time**, rise and fall separately — the same idea as
 *      the Android engine's `briRiseRate`/`briFallRate`. Nothing strobes: every
 *      change is a large soft gradient moving, never a cut. That is a comfort
 *      decision and a photosensitivity one.
 *
 * ## Why it is cheap
 *
 * TVs are slow. Everything is drawn to a **160x90 offscreen canvas** and scaled up to
 * the panel, which gives a free, smooth, hardware-accelerated blur and makes a
 * full-screen radial gradient cost about fourteen thousand pixels instead of two
 * million. At 30fps that is comfortably within budget on a 2019 panel.
 */

(function (global) {
    'use strict';

    // ── Palettes ─────────────────────────────────────────────────────────
    //
    // Ported from the Android app's SyncoPalette.kt so the TV and the Hue lamps
    // agree: picking "Ocean" on the television gives the colours "Ocean" gives the
    // bulbs. Expressed as HSV because that is how they were tuned, and because the
    // comfort pass below needs the hue and saturation separately.

    const PALETTES = {
        album:    { label: 'Album art', dynamic: true, colors: [[0.80, 0.80, 0.85], [0.92, 0.85, 1.0], [0.99, 0.85, 1.0], [0.045, 0.85, 1.0], [0.09, 0.80, 1.0], [0.12, 0.70, 1.0]] },
        sunset:   { label: 'Sunset',   colors: [[0.80, 0.80, 0.85], [0.92, 0.85, 1.0], [0.99, 0.85, 1.0], [0.045, 0.85, 1.0], [0.09, 0.80, 1.0], [0.12, 0.70, 1.0]] },
        ocean:    { label: 'Ocean',    colors: [[0.46, 0.75, 1.0], [0.50, 0.85, 1.0], [0.55, 0.90, 1.0], [0.60, 0.90, 1.0], [0.64, 0.85, 0.95]] },
        forest:   { label: 'Forest',   colors: [[0.27, 0.70, 1.0], [0.33, 0.80, 1.0], [0.38, 0.85, 0.95], [0.44, 0.80, 0.95]] },
        lavender: { label: 'Lavender', colors: [[0.72, 0.55, 1.0], [0.77, 0.65, 1.0], [0.83, 0.60, 1.0], [0.90, 0.55, 1.0]] },
        ember:    { label: 'Ember',    colors: [[0.99, 0.90, 1.0], [0.02, 0.90, 1.0], [0.05, 0.90, 1.0], [0.09, 0.85, 1.0], [0.12, 0.80, 1.0]] },
        aurora:   { label: 'Aurora',   colors: [[0.45, 0.80, 1.0], [0.36, 0.75, 1.0], [0.55, 0.80, 1.0], [0.72, 0.75, 1.0], [0.88, 0.65, 1.0]] },
        tropical: { label: 'Tropical', colors: [[0.92, 0.80, 1.0], [0.85, 0.72, 1.0], [0.98, 0.80, 1.0], [0.04, 0.82, 1.0], [0.10, 0.70, 1.0]] },
        blossom:  { label: 'Blossom',  colors: [[0.95, 0.42, 1.0], [0.04, 0.38, 1.0], [0.13, 0.33, 1.0], [0.78, 0.34, 1.0], [0.55, 0.28, 1.0]] },
        galaxy:   { label: 'Galaxy',   colors: [[0.66, 0.90, 1.0], [0.72, 0.85, 1.0], [0.78, 0.85, 1.0], [0.86, 0.78, 1.0]] },
        peacock:  { label: 'Peacock',  colors: [[0.515, 0.87, 0.90], [0.60, 0.88, 1.0], [0.44, 0.90, 0.85], [0.12, 0.80, 1.0]] },
        rosegold: { label: 'Rose gold', colors: [[0.04, 0.24, 1.0], [0.02, 0.45, 1.0], [0.04, 0.58, 0.88], [0.12, 0.62, 0.96]] },
    };

    /**
     * The scenes, ordered by how much they move.
     *
     * `auto` is the default and picks between the others from measured energy and
     * tempo, because the right answer genuinely changes between a piano piece and a
     * techno set and nobody wants to be changing a setting mid-album.
     */
    const SCENES = {
        auto:     { label: 'Auto' },
        glow:     { label: 'Glow', energy: 0 },
        aurora:   { label: 'Aurora', energy: 1 },
        pulse:    { label: 'Pulse', energy: 2 },
        spectrum: { label: 'Spectrum', energy: 3 },
        cover:    { label: 'Cover colours', energy: 1 },
    };

    // ── Colour maths ─────────────────────────────────────────────────────

    function hsvToRgb(h, s, v) {
        h = ((h % 1) + 1) % 1;
        const i = Math.floor(h * 6);
        const f = h * 6 - i;
        const p = v * (1 - s);
        const q = v * (1 - f * s);
        const t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0: return [v, t, p];
            case 1: return [q, v, p];
            case 2: return [p, v, t];
            case 3: return [p, q, v];
            case 4: return [t, p, v];
            default: return [v, p, q];
        }
    }

    function rgbToHsv(r, g, b) {
        const max = Math.max(r, g, b);
        const min = Math.min(r, g, b);
        const d = max - min;
        let h = 0;
        if (d > 1e-6) {
            if (max === r) h = ((g - b) / d) % 6;
            else if (max === g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        return [h, max <= 1e-6 ? 0 : d / max, max];
    }

    /** sRGB -> linear, for a luminance figure that means something. */
    function toLinear(c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Rec. 709 relative luminance of an sRGB triple. */
    function luminance(rgb) {
        return 0.2126 * toLinear(rgb[0]) + 0.7152 * toLinear(rgb[1]) + 0.0722 * toLinear(rgb[2]);
    }

    /**
     * Make one colour safe to look at in a dark room.
     *
     * This is the function the brief turns on, so the reasoning is worth stating.
     *
     * Clamping HSV *value* — the obvious thing — is wrong, because value says nothing
     * about how bright a colour actually is. Pure yellow and pure blue are both V=1
     * and yellow emits roughly ten times the light. A value clamp therefore lets
     * through exactly the colours that hurt and needlessly dims the ones that do not.
     * So the clamp is on **relative luminance**, and it scales the colour down until
     * it fits rather than desaturating it, which would turn every bright moment white.
     *
     * On top of that the warm arc gets an extra reduction: reds and oranges read as
     * harsher than their luminance alone predicts, and saturated red at scale is the
     * single most uncomfortable thing a panel can do at night. The reduction is
     * proportional to saturation, so a pale peach is barely touched and a fire-engine
     * red loses a third.
     *
     * @param {number[]} rgb 0..1 triple
     * @param {number} ceiling maximum relative luminance, from the user's setting
     */
    function comfort(rgb, ceiling) {
        const hsv = rgbToHsv(rgb[0], rgb[1], rgb[2]);
        const hue = hsv[0];
        const sat = hsv[1];

        // The warm arc: red (0) through orange to yellow (0.167), which is the whole
        // of what the brief called out, plus a taper at each outer edge so there is
        // no visible seam where the rule starts applying.
        //
        // The arc is flat across red..yellow rather than peaking in the middle of it.
        // An earlier version used a triangle centred on orange, which meant the
        // weight fell to **zero at pure red and pure yellow** — the exact two colours
        // this rule exists for. It looked reasonable and did nothing; the test caught
        // it.
        let warmth = 0;
        if (hue <= 0.167) {
            warmth = 1;
        } else if (hue < 0.25) {
            // Out through yellow-green, where it stops being a warm colour.
            warmth = 1 - (hue - 0.167) / (0.25 - 0.167);
        } else if (hue >= 0.95) {
            // In from magenta, which wraps round to red.
            warmth = (hue - 0.95) / 0.05;
        }
        const warmCut = 1 - WARM_ATTENUATION * Math.max(0, Math.min(1, warmth)) * sat;

        let out = [rgb[0] * warmCut, rgb[1] * warmCut, rgb[2] * warmCut];

        const lum = luminance(out);
        if (lum > ceiling && lum > 1e-6) {
            // Scaled in sRGB space rather than solved exactly in linear space: the
            // error is small at these levels and it keeps the hue intact, which an
            // exact per-channel solve does not.
            const k = Math.pow(ceiling / lum, 1 / 2.2);
            out = [out[0] * k, out[1] * k, out[2] * k];
        }
        return out;
    }

    /** How much of the warm arc is taken off at full saturation. Tuned by eye. */
    const WARM_ATTENUATION = 0.34;

    function css(rgb, alpha) {
        const r = Math.round(Math.max(0, Math.min(1, rgb[0])) * 255);
        const g = Math.round(Math.max(0, Math.min(1, rgb[1])) * 255);
        const b = Math.round(Math.max(0, Math.min(1, rgb[2])) * 255);
        return alpha === undefined
            ? 'rgb(' + r + ',' + g + ',' + b + ')'
            : 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
    }

    /**
     * A cyclic gradient over a palette, sampled by position.
     *
     * Supports **weights**, which is what makes an album-art palette resemble the
     * sleeve rather than being a smear of its colours: a cover that is 90% green and
     * 10% red should spend 90% of the cycle green, not half of it. So a weighted
     * palette *holds* each colour for its share and crossfades only near the
     * boundaries. Ported from the Android engine's `Palette`, where the same
     * reasoning is written out at more length.
     */
    function Gradient(colors, weights) {
        this.colors = colors && colors.length ? colors : [[0, 0, 1]];
        if (weights && weights.length === this.colors.length) {
            const total = weights.reduce(function (a, b) { return a + b; }, 0);
            this.weights = total > 1e-6 ? weights.map(function (w) { return Math.max(0, w / total); }) : null;
        } else {
            this.weights = null;
        }
    }

    /** How much of a cycle a weighted segment spends crossfading. Small on purpose. */
    const XFADE = 0.06;

    Gradient.prototype.sample = function (pos) {
        const n = this.colors.length;
        if (n === 1) return this.colors[0];
        const p = ((pos % 1) + 1) % 1;
        if (!this.weights) {
            const scaled = p * n;
            const i = Math.floor(scaled) % n;
            const j = (i + 1) % n;
            const f = scaled - Math.floor(scaled);
            const a = this.colors[i];
            const b = this.colors[j];
            return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f];
        }
        let start = 0;
        let i = n - 1;
        for (let k = 0; k < n; k++) {
            if (p < start + this.weights[k] || k === n - 1) { i = k; break; }
            start += this.weights[k];
        }
        const end = start + this.weights[i];
        const prev = this.colors[(i - 1 + n) % n];
        const next = this.colors[(i + 1) % n];
        const cur = this.colors[i];
        const xfIn = Math.min(XFADE, 0.5 * this.weights[i]);
        const xfOut = xfIn;
        let t, other;
        if (xfIn > 0 && p < start + xfIn) {
            t = 0.5 + 0.5 * (p - start) / xfIn;
            other = prev;
        } else if (xfOut > 0 && p > end - xfOut) {
            t = 1 - 0.5 * (1 - (end - p) / xfOut);
            other = next;
        } else {
            return cur;
        }
        return [
            other[0] + (cur[0] - other[0]) * t,
            other[1] + (cur[1] - other[1]) * t,
            other[2] + (cur[2] - other[2]) * t,
        ];
    };

    // ── The engine ───────────────────────────────────────────────────────

    function LightShow(options) {
        options = options || {};
        this.ambientCanvas = options.ambientCanvas || null;
        this.fullCanvas = options.fullCanvas || null;
        /** Called with an array of [r,g,b] 0..255 per light, when Hue is syncing. */
        this.onColors = options.onColors || null;

        this.scene = 'auto';
        this.paletteKey = 'album';
        /** Relative-luminance ceiling. 0.34 is easy on the eye in a dark room. */
        this.ceiling = 0.34;
        this.ambientEnabled = true;

        this.gradient = new Gradient(PALETTES.sunset.colors.map(function (c) {
            return hsvToRgb(c[0], c[1], c[2]);
        }));
        this.coverGradient = null;

        // Analysis state
        this.ctx = null;
        this.analyser = null;
        this.freq = null;
        this.prevFreq = null;
        this.reactive = false;
        this.silentFrames = 0;
        this.fluxHistory = [];

        // Render state
        this.running = false;
        this.raf = 0;
        this.lastT = 0;
        this.pos = 0;
        this.energy = 0;
        this.bass = 0;
        this.brightness = 0;
        this.targetBrightness = 0;
        this.beatFlash = 0;
        this.beatTimes = [];
        this.tempo = 0;
        this.fullscreen = false;

        this.offscreen = document.createElement('canvas');
        this.offscreen.width = LOW_W;
        this.offscreen.height = LOW_H;
        this.octx = this.offscreen.getContext('2d');
    }

    /**
     * The offscreen buffer the whole show is drawn into.
     *
     * 160x90 scaled to 1920x1080 is a 12x upscale, and the browser's own bilinear
     * filter turns that into a smooth blur for free — which is exactly the look
     * wanted, and which a real blur filter on a 2019 TV panel would not deliver at
     * thirty frames a second.
     */
    const LOW_W = 160;
    const LOW_H = 90;

    /** Rendering rate. Thirty is plenty for gradients and halves the TV's work. */
    const FPS = 30;

    // ── Audio tap ────────────────────────────────────────────────────────

    /**
     * Try to tap the audio element. Returns true if the tap is live.
     *
     * Everything about this is defensive on purpose — see the file header. The one
     * thing that must never happen is the music breaking because the visualiser
     * wanted a spectrum.
     */
    LightShow.prototype.attach = function (audioEl) {
        if (!audioEl || this.analyser) return this.reactive;
        const AC = global.AudioContext || global.webkitAudioContext;
        if (!AC) {
            console.warn('LightShow: no Web Audio on this TV — using the timed engine');
            return false;
        }
        try {
            this.ctx = new AC();
            const source = this.ctx.createMediaElementSource(audioEl);
            const analyser = this.ctx.createAnalyser();
            analyser.fftSize = 1024;
            analyser.smoothingTimeConstant = 0.6;
            source.connect(analyser);
            // Reconnected to the destination, or the tap would silence playback.
            // This is the line whose absence would be catastrophic and silent.
            analyser.connect(this.ctx.destination);
            this.analyser = analyser;
            this.freq = new Uint8Array(analyser.frequencyBinCount);
            this.prevFreq = new Uint8Array(analyser.frequencyBinCount);
            this.reactive = true;
            console.log('LightShow: audio tap live');
        } catch (e) {
            // A TV that refuses the graph, or an element already attached to one.
            console.warn('LightShow: audio tap unavailable —', e && e.message);
            this.ctx = null;
            this.analyser = null;
            this.reactive = false;
        }
        return this.reactive;
    };

    /** Browsers start an AudioContext suspended until a gesture. */
    LightShow.prototype.resume = function () {
        if (this.ctx && this.ctx.state === 'suspended') {
            this.ctx.resume().catch(function () {});
        }
    };

    // ── Analysis ─────────────────────────────────────────────────────────

    /**
     * One frame of features: broadband energy, bass envelope, spectral flux, beat.
     *
     * The beat test is the adaptive median-plus-MAD threshold the Android analyser
     * uses, ported: a flux value counts as an onset when it exceeds the median of the
     * last second by several median-absolute-deviations, with a refractory period so
     * one transient cannot fire twice. A fixed threshold does not survive the jump
     * from a quiet acoustic track to a loud master.
     */
    LightShow.prototype.analyse = function (dt) {
        if (!this.reactive || !this.analyser) return this.simulate(dt);

        this.analyser.getByteFrequencyData(this.freq);

        let sum = 0;
        let bassSum = 0;
        let flux = 0;
        const n = this.freq.length;
        // ~47 Hz per bin at 48 kHz with fftSize 1024, so six bins is roughly the
        // bass region and the first two hundred cover everything audible that
        // matters for a light show.
        const bassBins = 6;
        const top = Math.min(n, 220);
        for (let i = 0; i < top; i++) {
            const v = this.freq[i] / 255;
            sum += v;
            if (i < bassBins) bassSum += v;
            const d = this.freq[i] - this.prevFreq[i];
            if (d > 0) flux += d / 255;
            this.prevFreq[i] = this.freq[i];
        }

        // A tap that yields nothing while the element is playing is a tap that did
        // not really work — some TVs hand back a permanently zero spectrum rather
        // than throwing. Verified rather than trusted, and abandoned after a couple
        // of seconds so the fallback takes over while the first song is still on.
        if (sum <= 0.0001) {
            this.silentFrames++;
            if (this.silentFrames > FPS * 2) {
                console.warn('LightShow: tap produced only silence — falling back to the timed engine');
                this.reactive = false;
            }
        } else {
            this.silentFrames = 0;
        }

        const energy = Math.min(1, sum / (top * 0.45));
        const bass = Math.min(1, bassSum / (bassBins * 0.7));

        this.fluxHistory.push(flux);
        if (this.fluxHistory.length > FPS) this.fluxHistory.shift();

        let beat = false;
        if (this.fluxHistory.length >= FPS / 2) {
            const sorted = this.fluxHistory.slice().sort(function (a, b) { return a - b; });
            const med = sorted[Math.floor(sorted.length / 2)];
            const devs = this.fluxHistory.map(function (f) { return Math.abs(f - med); }).sort(function (a, b) { return a - b; });
            const mad = devs[Math.floor(devs.length / 2)];
            const threshold = med + 2.6 * mad + 0.02;
            if (flux > threshold && this.sinceBeat > 0.18) beat = true;
        }

        this.sinceBeat = beat ? 0 : (this.sinceBeat || 0) + dt;
        if (beat) {
            const now = performance.now() / 1000;
            this.beatTimes.push(now);
            if (this.beatTimes.length > 12) this.beatTimes.shift();
            this.tempo = this.estimateTempo();
        }

        return { energy: energy, bass: bass, beat: beat };
    };

    /** Median of recent beat intervals, as BPM. Mirrors the Android estimator. */
    LightShow.prototype.estimateTempo = function () {
        if (this.beatTimes.length < 4) return 0;
        const gaps = [];
        for (let i = 1; i < this.beatTimes.length; i++) {
            const g = this.beatTimes[i] - this.beatTimes[i - 1];
            if (g > 0.25 && g < 2.0) gaps.push(g);
        }
        if (gaps.length < 2) return 0;
        gaps.sort(function (a, b) { return a - b; });
        const mid = gaps[Math.floor(gaps.length / 2)];
        return mid > 0 ? 60 / mid : 0;
    };

    /**
     * The fallback: a plausible show with no spectrum at all.
     *
     * Not a placeholder. When the TV will not give up its audio this is the entire
     * feature, so it has to stand on its own — a slow breathing envelope at a
     * musically sensible period, with a gentle swell rather than a pulse, because
     * inventing beats that are not there looks worse than not having any.
     */
    LightShow.prototype.simulate = function (dt) {
        const t = performance.now() / 1000;
        const slow = 0.5 + 0.5 * Math.sin(t * 0.22);
        const faster = 0.5 + 0.5 * Math.sin(t * 0.63 + 1.1);
        return { energy: 0.35 + 0.25 * slow, bass: 0.3 + 0.3 * faster, beat: false };
    };

    // ── Palette selection ────────────────────────────────────────────────

    LightShow.prototype.setPalette = function (key) {
        this.paletteKey = key;
        const p = PALETTES[key] || PALETTES.sunset;
        if (p.dynamic && this.coverGradient) {
            this.gradient = this.coverGradient;
        } else {
            this.gradient = new Gradient(p.colors.map(function (c) {
                return hsvToRgb(c[0], c[1], c[2]);
            }));
        }
    };

    /**
     * Derive a palette from the album cover.
     *
     * Drawn into a 32x32 canvas and bucketed, which is enough: the show needs the
     * four or five colours a person would name looking at the sleeve, not an exact
     * quantisation. Buckets are weighted by how much of the cover they occupy and
     * the weights are handed to the gradient, so a mostly-green sleeve produces a
     * mostly-green room — see `Gradient`.
     *
     * Tainted-canvas safe. Reading pixels from a cross-origin image throws unless the
     * server sends CORS headers, and a self-hosted Navidrome may well not, so a
     * failure here quietly leaves the chosen static palette in place.
     */
    LightShow.prototype.setCoverArt = function (url) {
        const self = this;
        if (!url) { this.coverGradient = null; this.setPalette(this.paletteKey); return; }
        const img = new Image();
        img.crossOrigin = 'anonymous';
        img.onload = function () {
            try {
                const c = document.createElement('canvas');
                c.width = 32; c.height = 32;
                const cc = c.getContext('2d');
                cc.drawImage(img, 0, 0, 32, 32);
                const data = cc.getImageData(0, 0, 32, 32).data;
                const buckets = {};
                for (let i = 0; i < data.length; i += 4) {
                    const r = data[i] / 255, g = data[i + 1] / 255, b = data[i + 2] / 255;
                    const hsv = rgbToHsv(r, g, b);
                    // Near-black and near-grey pixels are the sleeve's background and
                    // its text. Including them gives every cover the same muddy
                    // palette, which is the failure mode of naive average-colour.
                    if (hsv[2] < 0.18 || hsv[1] < 0.16) continue;
                    const key = Math.floor(hsv[0] * 12) + ':' + Math.floor(hsv[1] * 3);
                    if (!buckets[key]) buckets[key] = { h: 0, s: 0, v: 0, n: 0 };
                    const bk = buckets[key];
                    bk.h += hsv[0]; bk.s += hsv[1]; bk.v += hsv[2]; bk.n++;
                }
                const list = Object.keys(buckets).map(function (k) {
                    const bk = buckets[k];
                    return { hsv: [bk.h / bk.n, bk.s / bk.n, bk.v / bk.n], n: bk.n };
                }).sort(function (a, b) { return b.n - a.n; }).slice(0, 5);

                if (list.length < 2) { self.coverGradient = null; self.setPalette(self.paletteKey); return; }

                self.coverGradient = new Gradient(
                    list.map(function (e) {
                        // Saturation floored and value lifted: a washed-out sleeve
                        // would otherwise produce a room that reads as broken rather
                        // than as subtle. The comfort pass caps the top end anyway.
                        return hsvToRgb(e.hsv[0], Math.max(0.45, e.hsv[1]), Math.max(0.65, e.hsv[2]));
                    }),
                    list.map(function (e) { return e.n; }),
                );
                if ((PALETTES[self.paletteKey] || {}).dynamic) self.gradient = self.coverGradient;
            } catch (e) {
                // Tainted canvas — the server did not allow the read.
                console.warn('LightShow: cover palette unavailable —', e && e.message);
                self.coverGradient = null;
            }
        };
        img.onerror = function () { self.coverGradient = null; };
        img.src = url;
    };

    // ── Scene selection ──────────────────────────────────────────────────

    /**
     * Which scene `auto` means right now.
     *
     * Hysteresis rather than a bare threshold: a track sitting on a boundary would
     * otherwise flip scenes several times a minute, which is far more distracting
     * than either scene.
     */
    LightShow.prototype.resolveScene = function () {
        if (this.scene !== 'auto') return this.scene;
        const e = this.energy;
        const fast = this.tempo > 118;
        let want;
        if (e < 0.28) want = 'glow';
        else if (e < 0.52) want = 'aurora';
        else want = fast ? 'spectrum' : 'pulse';
        if (want !== this.autoScene) {
            this.autoHold = (this.autoHold || 0) + 1;
            if (this.autoHold > FPS * 4) { this.autoScene = want; this.autoHold = 0; }
        } else {
            this.autoHold = 0;
        }
        return this.autoScene || want;
    };

    // ── Render ───────────────────────────────────────────────────────────

    LightShow.prototype.start = function () {
        if (this.running) return;
        this.running = true;
        this.lastT = performance.now();
        const self = this;
        const tick = function (now) {
            if (!self.running) return;
            const dt = Math.min(0.1, (now - self.lastT) / 1000);
            // Capped at 1/FPS: a TV that offers 60Hz gets half the frames, because
            // gradients do not need sixty and the panel has other work.
            if (dt >= 1 / FPS) {
                self.lastT = now;
                self.frame(dt);
            }
            self.raf = global.requestAnimationFrame(tick);
        };
        this.raf = global.requestAnimationFrame(tick);
    };

    LightShow.prototype.stop = function () {
        this.running = false;
        if (this.raf) global.cancelAnimationFrame(this.raf);
        this.raf = 0;
    };

    LightShow.prototype.frame = function (dt) {
        const f = this.analyse(dt);

        // Smoothed so a single loud frame does not jump the whole room.
        this.energy += (f.energy - this.energy) * Math.min(1, dt * 3.5);
        this.bass += (f.bass - this.bass) * Math.min(1, dt * 8);

        if (f.beat) this.beatFlash = 1;
        this.beatFlash *= Math.pow(0.02, dt);

        // Palette drift: faster when the music is busier, but never fast enough to
        // read as a colour cycle. A show that visibly loops is a screensaver.
        this.pos += dt * (0.012 + 0.05 * this.energy);

        this.targetBrightness = 0.25 + 0.55 * this.energy + 0.2 * this.beatFlash;

        // Rate-limited separately in each direction — the rise is looser so a beat
        // still lands, the fall is slow so nothing ever flickers. Same split, and the
        // same reasoning, as the Android engine's briRiseRate / briFallRate.
        const maxRise = RISE_PER_S * dt;
        const maxFall = FALL_PER_S * dt;
        const delta = this.targetBrightness - this.brightness;
        this.brightness += delta > 0 ? Math.min(delta, maxRise) : Math.max(delta, -maxFall);
        this.brightness = Math.max(0, Math.min(1, this.brightness));

        this.draw(this.resolveScene());
        this.publish();
    };

    /** Full scale per second. Deliberately asymmetric; see [frame]. */
    const RISE_PER_S = 2.6;
    const FALL_PER_S = 0.9;

    LightShow.prototype.colorAt = function (offset) {
        const raw = this.gradient.sample(this.pos + offset);
        const lit = [raw[0] * this.brightness, raw[1] * this.brightness, raw[2] * this.brightness];
        return comfort(lit, this.ceiling);
    };

    LightShow.prototype.draw = function (scene) {
        const g = this.octx;
        g.globalCompositeOperation = 'source-over';
        g.fillStyle = '#000';
        g.fillRect(0, 0, LOW_W, LOW_H);
        // Additive, so overlapping washes build light rather than painting over each
        // other — the same thing lamps in a room do.
        g.globalCompositeOperation = 'lighter';

        switch (scene) {
            case 'glow': this.drawGlow(g); break;
            case 'aurora': this.drawAurora(g); break;
            case 'pulse': this.drawPulse(g); break;
            case 'spectrum': this.drawSpectrum(g); break;
            case 'cover': this.drawGlow(g); break;
            default: this.drawGlow(g); break;
        }

        this.blit();
    };

    /** Two very large, very slow radial washes. The low-energy default. */
    LightShow.prototype.drawGlow = function (g) {
        const t = performance.now() / 1000;
        const a = this.colorAt(0);
        const b = this.colorAt(0.4);
        this.radial(g, LOW_W * (0.3 + 0.12 * Math.sin(t * 0.13)), LOW_H * (0.4 + 0.15 * Math.cos(t * 0.11)), LOW_W * 0.75, a, 0.85);
        this.radial(g, LOW_W * (0.72 + 0.12 * Math.cos(t * 0.09)), LOW_H * (0.62 + 0.14 * Math.sin(t * 0.15)), LOW_W * 0.7, b, 0.8);
    };

    /** Vertical ribbons that sway with the mid band. */
    LightShow.prototype.drawAurora = function (g) {
        const t = performance.now() / 1000;
        const bands = 4;
        for (let i = 0; i < bands; i++) {
            const c = this.colorAt(i / bands * 0.6);
            const x = LOW_W * ((i + 0.5) / bands) + Math.sin(t * 0.3 + i) * LOW_W * 0.08;
            const h = LOW_H * (0.55 + 0.35 * Math.sin(t * 0.45 + i * 1.3) + 0.2 * this.energy);
            const grad = g.createLinearGradient(x, LOW_H * 0.5 - h / 2, x, LOW_H * 0.5 + h / 2);
            grad.addColorStop(0, css(c, 0));
            grad.addColorStop(0.5, css(c, 0.85));
            grad.addColorStop(1, css(c, 0));
            g.fillStyle = grad;
            g.fillRect(x - LOW_W * 0.18, 0, LOW_W * 0.36, LOW_H);
        }
    };

    /** A centred wash that swells on the beat, with the colour stepping per beat. */
    LightShow.prototype.drawPulse = function (g) {
        const c = this.colorAt(0);
        const edge = this.colorAt(0.5);
        const r = LOW_W * (0.45 + 0.25 * this.bass + 0.15 * this.beatFlash);
        this.radial(g, LOW_W * 0.5, LOW_H * 0.5, r, c, 0.95);
        this.radial(g, LOW_W * 0.12, LOW_H * 0.85, LOW_W * 0.5, edge, 0.5);
        this.radial(g, LOW_W * 0.88, LOW_H * 0.15, LOW_W * 0.5, edge, 0.5);
    };

    /**
     * Frequency bars — soft-edged, and blurred by the upscale.
     *
     * Deliberately not the hard-edged bars a desktop visualiser draws: at this
     * upscale each bar becomes a soft column of light, which is the point. On the
     * fallback engine there is no spectrum, so the bars follow the simulated
     * envelope instead of standing still.
     */
    LightShow.prototype.drawSpectrum = function (g) {
        const bars = 16;
        for (let i = 0; i < bars; i++) {
            let v;
            if (this.reactive && this.freq) {
                const lo = Math.floor(Math.pow(i / bars, 1.7) * 200);
                const hi = Math.max(lo + 1, Math.floor(Math.pow((i + 1) / bars, 1.7) * 200));
                let s = 0;
                for (let k = lo; k < hi && k < this.freq.length; k++) s += this.freq[k] / 255;
                v = Math.min(1, s / (hi - lo) * 1.6);
            } else {
                const t = performance.now() / 1000;
                v = 0.3 + 0.4 * (0.5 + 0.5 * Math.sin(t * 1.1 + i * 0.7)) * this.energy * 2;
            }
            const c = this.colorAt(i / bars * 0.8);
            const w = LOW_W / bars;
            const h = LOW_H * (0.12 + 0.78 * Math.max(0, Math.min(1, v)));
            const grad = g.createLinearGradient(0, LOW_H, 0, LOW_H - h);
            grad.addColorStop(0, css(c, 0.95));
            grad.addColorStop(1, css(c, 0));
            g.fillStyle = grad;
            g.fillRect(i * w, LOW_H - h, w, h);
        }
    };

    LightShow.prototype.radial = function (g, x, y, r, color, alpha) {
        const grad = g.createRadialGradient(x, y, 0, x, y, Math.max(1, r));
        grad.addColorStop(0, css(color, alpha));
        grad.addColorStop(0.55, css(color, alpha * 0.45));
        grad.addColorStop(1, css(color, 0));
        g.fillStyle = grad;
        g.fillRect(0, 0, LOW_W, LOW_H);
    };

    /** Scale the buffer onto whichever canvases are live. */
    LightShow.prototype.blit = function () {
        const target = this.fullscreen ? this.fullCanvas : (this.ambientEnabled ? this.ambientCanvas : null);
        if (!target) return;
        const c = target.getContext('2d');
        if (target.width !== target.clientWidth || target.height !== target.clientHeight) {
            target.width = target.clientWidth || 1920;
            target.height = target.clientHeight || 1080;
        }
        c.clearRect(0, 0, target.width, target.height);
        c.imageSmoothingEnabled = true;
        c.drawImage(this.offscreen, 0, 0, target.width, target.height);
    };

    /**
     * Hand the same colours to the Hue service.
     *
     * The JS service has exposed `updateLights` since it was written and nothing ever
     * called it, so webOS Hue sync sent no colours at all. Feeding it from here means
     * the lamps and the television agree, which is the point of having both.
     *
     * Sampled around the gradient rather than averaged: lamps spread across a room
     * should not all be the same colour.
     */
    LightShow.prototype.publish = function () {
        if (!this.onColors) return;
        this.publishTick = (this.publishTick || 0) + 1;
        // ~15/s. The bridge accepts far more, but each one is a Luna IPC round trip
        // and the web layer has a TV to draw.
        if (this.publishTick % 2) return;
        const out = [];
        for (let i = 0; i < 12; i++) {
            const c = this.colorAt(i / 12 * 0.6);
            out.push([Math.round(c[0] * 255), Math.round(c[1] * 255), Math.round(c[2] * 255)]);
        }
        try { this.onColors(out); } catch (e) { /* the service is optional */ }
    };

    // ── Public surface ───────────────────────────────────────────────────

    LightShow.prototype.setScene = function (key) { this.scene = SCENES[key] ? key : 'auto'; };
    LightShow.prototype.setCeiling = function (v) { this.ceiling = Math.max(0.08, Math.min(0.75, v)); };
    LightShow.prototype.setAmbient = function (on) {
        this.ambientEnabled = !!on;
        if (!on && this.ambientCanvas) {
            const c = this.ambientCanvas.getContext('2d');
            c.clearRect(0, 0, this.ambientCanvas.width, this.ambientCanvas.height);
        }
    };
    LightShow.prototype.setFullscreen = function (on) {
        this.fullscreen = !!on;
        // Clear whichever canvas is being left, or its last frame sits there frozen.
        const stale = on ? this.ambientCanvas : this.fullCanvas;
        if (stale) {
            const c = stale.getContext('2d');
            c.clearRect(0, 0, stale.width, stale.height);
        }
    };

    global.LightShow = LightShow;
    global.LightShowPalettes = PALETTES;
    global.LightShowScenes = SCENES;
})(window);
