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

// Place search
{
  const pl = (name, kind, rank, lon, lat, near) => ({ type: 'Feature', properties: { name, kind, rank, near }, geometry: { type: 'Point', coordinates: [lon, lat] } });
  const ps = [pl('Mandurah', 'city', 1, 115.72, -32.53), pl('Mary Street boat ramp, Mandurah', 'ramp', 5, 115.72, -32.53, 'Mandurah'),
    pl('Boat ramp, Falcon', 'ramp', 5, 115.66, -32.58, 'Falcon'), pl("Hillarys Boat Harbour", 'harbour', 4, 115.74, -31.82, 'Sorrento'),
    pl('Halls Head', 'suburb', 4, 115.70, -32.54, 'Mandurah')];
  assert.equal(C.searchPlaces(ps, 'mandurah', null)[0].properties.name, 'Mandurah');
  assert.deepEqual(C.searchPlaces(ps, 'ramp mandurah', null).map((f) => f.properties.name), ['Mary Street boat ramp, Mandurah']);
  assert.equal(C.searchPlaces(ps, "hillary's", null).length, 1);
  assert.equal(C.searchPlaces(ps, 'boat ramp', { lat: -32.58, lon: 115.66 })[0].properties.name, 'Boat ramp, Falcon');
  assert.equal(C.searchPlaces(ps, '  ', null).length, 0);
  assert.equal(C.fold('Ēxmouth, W.A.'), 'exmouth wa');
}

// Depth source text
assert.deepEqual(C.sourceLines({ src: 'WA DoT soundings, Kalbarri 1989 (single beam)', acc: 3, near: 500 }),
  ['Source: WA DoT soundings, Kalbarri 1989 (single beam)', 'Accuracy: Poor: estimated between survey lines. Reefs between them can be missing.', 'Nearest measured depth: up to 500 m away.']);
assert.equal(C.sourceLines({ src: 'x', acc: 1, near: 100 })[2], 'Nearest measured depth: within 100 m.');
assert.equal(C.sourceLines(null).length, 0);

// Backups: round trip, photo ids survive, no track or settings go to the phone
const state = { routes: [{ id: 'r1', name: 'A & <b>', waypoints: [{ lat: -32, lon: 115.5, name: 'x' }] }], plan: C.emptyRoute(),
  marks: [{ id: 'm1', lat: -32, lon: 115.6, name: 'Spot', time: 1700000000000, depth: '3–4', photos: ['p1'], group: 'Reefs' }], groups: [{ name: 'Reefs', color: '#E53935', visible: false }], track: [{ lat: 1, lon: 2, t: 3 }] };
const out = JSON.parse(C.exportBackup(state));
assert.deepEqual(Object.keys(out).sort(), ['app', 'exported', 'format', 'markGroups', 'marks', 'plan', 'routes']);
const back = C.parseBackup(JSON.stringify(out));
assert.deepEqual(back.marks, state.marks);
assert.deepEqual(back.routes, state.routes);
assert.deepEqual(back.groups, state.groups);
assert.ok(C.exportGpx(state).includes('<type>Reefs</type>'));

// Groups: an old file without groups keeps the group we have; an explicit '' clears it
const old = { id: 'm1', lat: 0, lon: 0, name: 'n', time: 1 };
assert.equal(C.mergeMarks(state.marks, [C.cleanMark(old)])[0].group, 'Reefs');
assert.equal(C.mergeMarks(state.marks, [C.cleanMark({ ...old, group: '' })])[0].group, '');
const gs = [{ name: 'Reefs', color: '#FDD835', visible: true }], ms = [{ group: 'reefs' }, { group: 'Boats' }, {}];
C.tidyGroups(gs, ms);
assert.deepEqual(gs.map((g) => g.name), ['', 'Boats', 'Reefs']);
assert.deepEqual(ms.map((m) => m.group), ['Reefs', 'Boats', '']);
assert.equal(C.groupName('  ungrouped '), '');
assert.equal(C.cleanGroup({ name: 'x', color: 'red;}' }).color, '#FDD835');
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
// Navigation
near(C.xteNm({ lat: -32, lon: 115.01 }, { lat: -32.1, lon: 115 }, { lat: -31.9, lon: 115 }), 0.51, 0.02);   // east of a northbound leg = right
assert.ok(C.xteNm({ lat: -32, lon: 114.99 }, { lat: -32.1, lon: 115 }, { lat: -31.9, lon: 115 }) < 0);
near(C.sogCog({ lat: -32, lon: 115, t: 0 }, { lat: -31.99, lon: 115, t: 3600000 }).sog, 0.6, 0.01);
assert.equal(C.compass(200), 'SSW');

// Tides at Fremantle: about ±0.4 m, highs and lows taking turns
const asset = (f) => JSON.parse(fs.readFileSync(new URL(`../android/app/src/main/assets/${f}`, import.meta.url), 'utf8'));
{
  const T = new C.Tides(asset('tides.json'));
  const st = T.nearest(-32.06, 115.74);
  assert.equal(st.near, 'Fremantle');
  assert.equal(T.nearest(-25, 125), null);
  const t0 = Date.UTC(2026, 10, 28), turns = T.turns(st, t0, t0 + 3 * 86400000);
  assert.ok(turns.length >= 4);
  turns.slice(1).forEach((x, i) => assert.notEqual(x.high, turns[i].high));
  for (const x of turns) assert.ok(Math.abs(x.metres) < 0.8);
}

// Bag limits and the catch check (mirror BagLimits.kt and CatchLimits.kt)
{
  const L = new C.BagLimits(asset('bag_limits.json'), asset('limit_areas.json'));
  assert.equal(L.areaAt({ lat: -32.2, lon: 115.7 }), 'west');
  assert.equal(L.areaAt({ lat: -28.7, lon: 113.8 }), 'abrolhos');
  assert.equal(L.areaAt({ lat: -35, lon: 118 }), 'south');
  assert.equal(L.areaAt({ lat: 0, lon: 0 }), null);
  const names = (q) => L.lookup('west', q).map(([, e]) => e.name);
  assert.deepEqual(names('Dhufish'), ['Dhufish, West Australian']);
  assert.deepEqual(names('snapper'), ['Pink snapper']);
  assert.deepEqual(names('Tailors'), ['Tailor']);
  assert.deepEqual(names('Silver trevally (skippy)'), ['Silver trevally']);
  assert.equal(names('bluebone').length, 3);
  const at = { lat: -32.2, lon: 115.72 };
  const tailor = C.checkCatch(L, { species: 'Tailor', count: 3, lengthCm: 25, pos: at }, [{ species: 'tailor', count: 6, time: Date.now(), released: false }]);
  assert.ok(tailor.problems.some((x) => x.startsWith('Undersize: 25 cm')));
  assert.ok(tailor.problems.some((x) => x.startsWith('Over the bag: 9 kept today')));
  assert.deepEqual(C.checkCatch(L, { species: 'Tailor', count: 1, lengthCm: 35, pos: at }).problems, []);
  assert.ok(C.checkCatch(L, { species: 'Barramundi', lengthCm: 85, pos: { lat: -17.9, lon: 122.2 } }).problems[0].startsWith('Oversize'));
  assert.ok(C.checkCatch(L, { species: 'Grey nurse shark', pos: at }).problems[0].startsWith('Protected'));
  assert.ok(C.checkCatch(L, { species: 'Pink snapper', time: Date.UTC(2026, 9, 1), pos: { lat: -32.2, lon: 115.72 } }).problems.some((x) => x.startsWith('Closed here now')));
  assert.equal(C.checkCatch(L, { species: 'Tailor', pos: null }).lines[0], "No position, so the area's limits aren't known.");
}

// Catches: same JSON as the phone, CSV safe from formulas, and they go to the phone in the backup
{
  const c = C.cleanCatch({ id: 'c1', time: 5, species: '=Tailor', count: '2', cm: '31.5', lat: -32, lon: 115, mark: 'm1', photos: ['p'] });
  assert.deepEqual(c, { id: 'c1', time: 5, species: '=Tailor', count: 2, released: false, bait: '', notes: '', lat: -32, lon: 115, cm: 31.5, photos: ['p'], mark: 'm1' });
  assert.ok(C.catchesCsv([c]).split('\n')[1].includes(`"'=Tailor",2,31.5,,kept,-32.000000,115.000000`));
  assert.equal(C.catchTitle(c), '2 × =Tailor');
  const withCatch = JSON.parse(C.exportBackup({ ...state, catches: [c] }));
  assert.equal(withCatch.catches[0].id, 'c1');
  assert.equal(C.parseBackup(JSON.stringify(withCatch)).catches[0].cm, 31.5);
}

// Forecasts
{
  const f = C.parseForecast({ hourly: { time: [0, 3600], wind_speed_10m: [10, 12], wind_gusts_10m: [15, null], wind_direction_10m: [225, 230] } },
    { hourly: { time: [3600], wave_height: [1.2], wind_wave_height: [0.5], wind_wave_period: [4], wind_wave_direction: [200], swell_wave_height: [1.0], swell_wave_period: [12], swell_wave_direction: [240] } }, -32, 115, 1);
  assert.equal(C.windText(C.forecastAt(f, 0)), '10 kn gusting 15 from SW');
  assert.equal(C.wavesText(C.forecastAt(f, 3600000)), 'Swell 1.0 m 12 s from WSW · Sea 0.5 m 4 s from SSW · Combined 1.2 m');
  assert.equal(C.forecastAt(f, 10 * 3600000), null);
}
console.log('planner tests passed');
