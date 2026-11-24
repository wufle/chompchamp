// node web/test.mjs: checks the planner's logic without a browser.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

vm.runInThisContext(fs.readFileSync(new URL('./src/core.js', import.meta.url), 'utf8'));
const C = globalThis.ChompCore;
const near = (a, b, e) => assert.ok(Math.abs(a - b) < e, `${a} is not near ${b}`);

// Geodesy and formatting
near(C.distanceNm({ lat: -32, lon: 115 }, { lat: -33, lon: 115 }), 60.04, 0.05);
near(C.bearing({ lat: -32, lon: 115 }, { lat: -32, lon: 116 }), 90, 0.5);
near(C.segDistM({ lat: -32, lon: 115.5 }, { lat: -32.001, lon: 115 }, { lat: -32.001, lon: 116 }), 111, 1);
assert.equal(C.fmtDdm(-31.99999999, true), "32°00.000'S");
assert.equal(C.fmtDdm(115.5, false), "115°30.000'E");
assert.equal(C.fmtBearing(359.7), '000°');
assert.equal(C.fmtDuration(1.5), '1h 30m');

// Backups: round trip, photo ids survive, no track or settings go to the phone
const state = { routes: [{ id: 'r1', name: 'A & <b>', waypoints: [{ lat: -32, lon: 115.5, name: 'x' }] }], plan: C.emptyRoute(),
  marks: [{ id: 'm1', lat: -32, lon: 115.6, name: 'Spot', time: 1700000000000, depth: '3–4', photos: ['p1'] }], track: [{ lat: 1, lon: 2, t: 3 }] };
const out = JSON.parse(C.exportBackup(state));
assert.deepEqual(Object.keys(out).sort(), ['app', 'exported', 'format', 'marks', 'plan', 'routes']);
const back = C.parseBackup(JSON.stringify(out));
assert.deepEqual(back.marks, state.marks);
assert.deepEqual(back.routes, state.routes);
assert.equal(C.parseBackup('{"app":"ChompChamp","track":[-32,115,-32.1,115]}').track.length, 2);
assert.equal(C.parseBackup('{"app":"ChompChamp","track3":[-32,115,5]}').track[0].t, 5);
assert.throws(() => C.parseBackup('{"app":"Other"}'));
assert.throws(() => C.parseBackup('{"app":"ChompChamp","marks":[{"id":"1","lat":"x","lon":0}]}'));
assert.ok(C.exportGpx(state).includes('A &amp; &lt;b&gt;'));
assert.deepEqual(C.mergeById([{ id: 1, v: 'a' }], [{ id: 1, v: 'b' }, { id: 2, v: 'c' }]), [{ id: 1, v: 'b' }, { id: 2, v: 'c' }]);

// Route check against a pretend chart: land east of 115.6, shallow from 115.55, unknown west of 115.4
const reader = { async at(p) {
  if (p.lon < 115.4) return { known: true, land: false, depth: null, zones: [] };
  if (p.lon > 115.6) return { known: true, land: true, depth: [0, 1], zones: [] };
  return { known: true, land: false, depth: p.lon > 115.55 ? [2, 3] : [10, 12], zones: p.lon > 115.5 && p.lon < 115.52 ? [{ zone_class: 'notake', name: 'Z' }] : [] };
} };
const rock = { properties: { kind: 'rock', name: 'Bommie' }, geometry: { coordinates: [115.45, -32.0003] } };
const wp = (lon) => ({ lat: -32, lon, name: '' });
const all = (await C.checkRoute(reader, [wp(115.35), wp(115.65)], 5, [rock], { stepM: 50 })).map((i) => i.text).join('|');
for (const t of ['crosses land', 'under 5 m (shallowest band starts at 2 m)', 'no depth data', 'from a rock (Bommie)', 'no-fishing zone: Z']) assert.ok(all.includes(t), t);
assert.deepEqual(await C.checkRoute(reader, [wp(115.46), wp(115.49)], 5, [rock], { stepM: 50 }), []);
console.log('planner tests passed');
