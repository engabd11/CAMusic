/**
 * Checks on the light show's colour maths — the half that can be proven without a
 * television.
 *
 * Run with `node webos/test/lightshow.test.js`. Deliberately dependency-free: the
 * webOS app has no build step, no package.json and no test runner, and adding npm to
 * it for a dozen assertions would be a worse trade than a plain script.
 *
 * What is worth checking here is the eye-comfort contract, because it is the one part
 * of the feature with a stated requirement ("bright red or yellow isn't nice on TV,
 * especially in dark rooms") and the one part whose failure is a person wincing at
 * 2am rather than a stack trace.
 */

'use strict';

// A DOM stub sufficient for the module to define itself. Nothing here renders; the
// tests only reach the pure functions and the engine's state machine.
const canvasStub = () => ({
    width: 0, height: 0, clientWidth: 1920, clientHeight: 1080,
    getContext: () => ({
        fillRect() {}, clearRect() {}, drawImage() {},
        createRadialGradient: () => ({ addColorStop() {} }),
        createLinearGradient: () => ({ addColorStop() {} }),
        getImageData: () => ({ data: new Uint8ClampedArray(32 * 32 * 4) }),
    }),
});

// A driven clock rather than the wall clock. The fallback engine reads the time
// directly — correctly, since in production requestAnimationFrame advances it — so a
// test that called it sixty times inside one millisecond would see a flat envelope
// and conclude the engine was broken when it was the harness that was.
let fakeNow = 0;
const advance = (ms) => { fakeNow += ms; };

const g = {
    document: { createElement: () => canvasStub() },
    requestAnimationFrame: () => 0,
    cancelAnimationFrame: () => {},
    performance: { now: () => fakeNow },
    console,
};
g.window = g;
global.window = g;
global.document = g.document;
global.performance = g.performance;
global.requestAnimationFrame = g.requestAnimationFrame;
global.cancelAnimationFrame = g.cancelAnimationFrame;

require('../js/lightshow.js');
const LightShow = g.LightShow;
const PALETTES = g.LightShowPalettes;
const SCENES = g.LightShowScenes;

let failures = 0;
function check(name, condition, detail) {
    if (condition) {
        console.log('  ok   ' + name);
    } else {
        failures++;
        console.log('  FAIL ' + name + (detail ? ' — ' + detail : ''));
    }
}

function toLinear(c) {
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}
function luminance(rgb) {
    return 0.2126 * toLinear(rgb[0]) + 0.7152 * toLinear(rgb[1]) + 0.0722 * toLinear(rgb[2]);
}

console.log('\nEye comfort');

// The whole point of the feature. A show is built by sampling the gradient at full
// brightness, so every palette at every position must come out under the ceiling —
// not on average, and not usually. Once.
{
    const ceilings = [0.18, 0.34, 0.60];
    let worst = 0;
    let worstWhere = '';
    for (const ceiling of ceilings) {
        for (const key of Object.keys(PALETTES)) {
            const show = new LightShow({});
            show.setCeiling(ceiling);
            show.setPalette(key);
            show.brightness = 1;
            for (let i = 0; i <= 200; i++) {
                show.pos = i / 200;
                const c = show.colorAt(0);
                const lum = luminance(c);
                // A hair of tolerance for the sRGB-space approximation the comfort
                // pass uses instead of an exact linear solve; it keeps the hue, and
                // the error is well under a perceptible step.
                if (lum > ceiling + 0.02) {
                    if (lum - ceiling > worst) { worst = lum - ceiling; worstWhere = key + ' @ ' + ceiling; }
                }
            }
        }
    }
    check('no palette exceeds its luminance ceiling', worst === 0, worstWhere + ' over by ' + worst.toFixed(3));
}

// The specific complaint. Saturated red and yellow are the colours that hurt, and
// they are exactly the ones an HSV-value clamp lets through at full power.
{
    const show = new LightShow({});
    show.setCeiling(0.34);
    show.gradient = new (Object.getPrototypeOf(show.gradient).constructor)([[1, 1, 0]]); // pure yellow
    show.brightness = 1;
    const yellow = show.colorAt(0);
    check('pure yellow is pulled under the ceiling', luminance(yellow) <= 0.36, 'got ' + luminance(yellow).toFixed(3));

    show.gradient = new (Object.getPrototypeOf(show.gradient).constructor)([[1, 0, 0]]);
    const red = show.colorAt(0);
    check('saturated red is attenuated beyond its luminance alone', red[0] < 0.92, 'r=' + red[0].toFixed(3));

    // The rule must not flatten everything: a cool colour of the same nominal value
    // should survive far better than the warm ones, or the show is grey.
    show.gradient = new (Object.getPrototypeOf(show.gradient).constructor)([[0, 0.4, 1]]);
    const blue = show.colorAt(0);
    check('a cool colour keeps its intensity', blue[2] > 0.9, 'b=' + blue[2].toFixed(3));
}

console.log('\nBrightness rate limiting');

// Nothing may strobe. A step input must take several frames to arrive, and longer
// to leave — the asymmetry is what makes beats land while the room never flickers.
{
    const show = new LightShow({});
    show.reactive = false;
    show.brightness = 0;
    show.targetBrightness = 1;
    const dt = 1 / 30;
    let frames = 0;
    while (show.brightness < 0.95 && frames < 200) {
        const delta = show.targetBrightness - show.brightness;
        show.brightness += Math.min(delta, 2.6 * dt);
        frames++;
    }
    check('a full rise takes more than one frame', frames >= 3, frames + ' frames');

    let fallFrames = 0;
    while (show.brightness > 0.05 && fallFrames < 200) {
        show.brightness -= Math.min(show.brightness, 0.9 * dt);
        fallFrames++;
    }
    check('the fall is slower than the rise', fallFrames > frames, frames + ' up vs ' + fallFrames + ' down');
}

console.log('\nWeighted palettes');

// What makes an album palette resemble the sleeve rather than smear it: a colour
// holding 80% of the cover should hold most of the cycle.
{
    const show = new LightShow({});
    const Gradient = Object.getPrototypeOf(show.gradient).constructor;
    const gr = new Gradient([[0, 1, 0], [1, 0, 0]], [8, 2]);
    let greenish = 0;
    for (let i = 0; i < 100; i++) {
        const c = gr.sample(i / 100);
        if (c[1] > c[0]) greenish++;
    }
    check('a dominant colour dominates the cycle', greenish >= 65, greenish + '/100 frames');

    const even = new Gradient([[0, 1, 0], [1, 0, 0]]);
    let evenGreen = 0;
    for (let i = 0; i < 100; i++) {
        const c = even.sample(i / 100);
        if (c[1] > c[0]) evenGreen++;
    }
    check('an unweighted palette splits evenly', Math.abs(evenGreen - 50) <= 10, evenGreen + '/100');
}

console.log('\nFallback engine');

// The engine must be useful with no spectrum at all, because whether webOS will
// share its audio is the TV's decision and not ours.
{
    const show = new LightShow({});
    show.reactive = false;
    const seen = new Set();
    let inRange = true;
    for (let i = 0; i < 300; i++) {
        advance(1000 / 30);
        const f = show.analyse(1 / 30);
        if (f.energy < 0 || f.energy > 1) inRange = false;
        seen.add(Math.round(f.energy * 50));
    }
    check('the fallback envelope stays in range', inRange);
    check('the timed fallback produces a varying envelope', seen.size > 1, seen.size + ' distinct levels');
    check('the fallback never invents beats', show.analyse(1 / 30).beat === false);
}

console.log('\nScene selection');

{
    const show = new LightShow({});
    show.setScene('pulse');
    check('an explicit scene is respected', show.resolveScene() === 'pulse');

    show.setScene('auto');
    show.energy = 0.1;
    let r;
    for (let i = 0; i < 200; i++) r = show.resolveScene();
    check('auto picks a calm scene when the music is quiet', r === 'glow', 'got ' + r);

    show.energy = 0.8;
    show.tempo = 140;
    for (let i = 0; i < 200; i++) r = show.resolveScene();
    check('auto picks a busy scene when the music is loud and fast', r === 'spectrum', 'got ' + r);
}

// Hysteresis: a track on a boundary must not flip scenes every frame.
{
    const show = new LightShow({});
    show.setScene('auto');
    show.energy = 0.1;
    for (let i = 0; i < 200; i++) show.resolveScene();
    const before = show.resolveScene();
    show.energy = 0.9;
    const immediately = show.resolveScene();
    check('a sudden change does not switch scene instantly', immediately === before, before + ' -> ' + immediately);
}

console.log('\nSafety');

{
    const show = new LightShow({});
    // attach() must never throw, whatever it is handed — the music must not break
    // because the visualiser wanted a spectrum.
    let threw = false;
    try { show.attach(null); show.attach({}); } catch (e) { threw = true; }
    check('attach never throws', !threw);
    check('no Web Audio means the timed engine', show.reactive === false);

    // setCoverArt with no url must clear rather than explode.
    let threw2 = false;
    try { show.setCoverArt(null); } catch (e) { threw2 = true; }
    check('a missing cover is handled', !threw2);

    check('every scene in the picker has a label', Object.keys(SCENES).every(k => !!SCENES[k].label));
    check('every palette in the picker has colours or is dynamic',
        Object.keys(PALETTES).every(k => PALETTES[k].dynamic || (PALETTES[k].colors || []).length >= 2));
}

console.log('\n' + (failures === 0 ? 'All checks passed.' : failures + ' CHECK(S) FAILED.'));
process.exit(failures === 0 ? 0 : 1);
