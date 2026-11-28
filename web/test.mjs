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
// Navigator: arrival circle, passing abeam, skip and back
{
  const nav = new C.Navigator();
  const r = { id: 'r', name: 'R', waypoints: [{ lat: -32, lon: 115.5, name: 'A' }, { lat: -32.1, lon: 115.5, name: 'B' }, { lat: -32.2, lon: 115.5, name: 'C' }] };
  nav.start(r, { lat: -31.9, lon: 115.5 });
  let d = nav.update({ lat: -31.95, lon: 115.5 }, 10, 180, 0.05);
  assert.equal(d.target.name, 'A'); near(d.bearing, 180, 0.5); near(d.vmg, 10, 0.01); assert.equal(d.arrived, null);
  d = nav.update({ lat: -32.0002, lon: 115.5 }, 10, 180, 0.05);
  assert.equal(d.arrived, 'A'); assert.equal(d.target.name, 'B');
  // Passed B abeam, 0.15 nm off: close enough with a 0.05 nm circle (4x).
  d = nav.update({ lat: -32.1005, lon: 115.503 }, 10, 180, 0.05);
  assert.equal(d.arrived, 'B'); assert.equal(d.target.name, 'C');
  assert.ok(nav.previous({ lat: -32.1, lon: 115.5 })); assert.equal(nav.target.name, 'B');
  assert.ok(nav.skip()); assert.equal(nav.target.name, 'C'); assert.ok(!nav.skip());
  d = nav.update({ lat: -32.2, lon: 115.5 }, 0, null, 0.05);
  assert.ok(d.finished);
  const saved = new C.Navigator(JSON.parse(JSON.stringify(nav)));
  assert.equal(saved.target.name, 'C');
  near(C.alongTrackNm({ lat: -32, lon: 115 }, { lat: -33, lon: 115 }, { lat: -32.5, lon: 115.01 }), 30, 0.1);
  const p = C.destination({ lat: -32, lon: 115 }, 90, 1);
  near(C.distanceNm({ lat: -32, lon: 115 }, p), 1, 1e-6); near(C.bearing({ lat: -32, lon: 115 }, p), 90, 0.01);
  assert.equal(C.fmtXte(-0.02), '37 m L'); assert.equal(C.fieldNm(1.234), '1.23');
}

// Drift, trips, track encoding
{
  const dr = new C.DriftTracker();
  dr.add(0, { lat: -32, lon: 115 }, 60);
  assert.equal(dr.drift(), null);
  dr.add(60000, { lat: -32 - 1 / 60 / 60, lon: 115 }, 60);   // 1 nm/h south
  near(dr.drift().kn, 1, 0.01); near(dr.drift().dir, 180, 0.5);
  const pts = [];
  for (let i = 0; i < 20; i++) C.addTrackPoint(pts, { lat: -32 - i * 0.001, lon: 115 }, 1e12 + i * 10000, 6, 10);
  assert.equal(pts.length, 20);
  assert.ok(!C.addTrackPoint(pts.slice(), { lat: -32.019, lon: 115 }, 1e12 + 191000, 0, 10));   // under 10 m, and soon after
  assert.ok(C.addTrackPoint(pts.slice(), { lat: -32.019, lon: 115 }, 1e12 + 190000 + 120000, 0, 10));   // stopped: repeated every 2 min
  const st = C.TripStats.of(pts);
  near(st.distanceNm, 19 * 0.06, 0.01); assert.equal(st.maxKn, 6);
  const enc = C.encodeTrip(pts), dec = C.decodeTrip(enc);
  assert.equal(dec.length, pts.length); assert.equal(dec[5].t, pts[5].t); assert.equal(dec[5].sog, 6); near(dec[5].lat, pts[5].lat, 1e-6);
  assert.equal(C.decodeTrip('-32,115,5,;bad;-32.1,115,10,30')[1].sog, 3);
  const t = C.tripOf(pts);
  assert.equal(t.id, 'trip-1000000000000'); assert.equal(t.n, 20);
  assert.equal(C.splitTrack([...pts, { lat: -32, lon: 115, t: 1e12 + 9e6, sog: -1 }], 3600000).length, 2);
  assert.equal(C.archiveTrack(pts.slice(0, 2), null, false, () => false).length, 0);   // never left the spot
  assert.equal(C.archiveTrack(pts, null, false, () => false).length, 1);
  const cs = [{ id: 'a', time: 1e12 + 50000 }, { id: 'b', time: 1e12 + 5 * 3600000 }];
  assert.deepEqual(C.catchesIn(cs, t.start, t.end, []).map((c) => c.id), ['a']);
  // Backups carry trips both ways, in the phone's format.
  const out = JSON.parse(C.exportBackup({ routes: [], plan: C.emptyRoute(), marks: [], groups: [], catches: [], track: pts, trips: [{ trip: t, pts: enc }] }, ['trips', 'track']));
  assert.equal(out.trips[0].pts, enc); assert.equal(out.track3.length, 60); assert.equal(out.trackSog[0], 6); assert.equal(out.routes, undefined);
  const back = C.parseBackup(JSON.stringify(out));
  assert.equal(back.trips[0].points.length, 20); assert.equal(back.track[3].sog, 6); assert.equal(back.trips[0].trip.name, t.name);
  assert.ok(C.tripGpx(t, pts, []).includes('<trkpt'));
  assert.equal(C.speedCsv(pts).split('\n')[1].split(',')[3], '6.0');
}

// Wind grid: the fetch box, and reading it back
{
  const g = C.windGrid(115.5, -32.2, 115.9, -31.9);
  assert.ok(g.nx <= 17 && g.ny <= 17); assert.equal(g.pts.length, g.nx * g.ny);
  assert.ok(g.w <= 115.5 && g.e >= 115.9 && g.s <= -32.2 && g.n >= -31.9);
  const m = g.pts.length, hours = 3;
  const one = () => ({ hourly: { time: [0, 3600, 7200], wind_speed_10m: [10, 20, 30], wind_direction_10m: [270, 270, 270], wind_gusts_10m: [15, 25, 35] } });
  const wave = () => ({ hourly: { time: [0, 3600, 7200], wave_height: [1, 2, 3], wind_wave_height: [0.5, 1, 1], wind_wave_direction: [200, 200, 200], wind_wave_period: [4, 4, 4],
    swell_wave_height: [1, 1.5, 2.5], swell_wave_direction: [240, 240, 240], swell_wave_period: [12, 12, 12] } });
  const parts = (f) => { const a = []; for (let i = 0; i < m; i += 100) a.push(Array.from({ length: Math.min(100, m - i) }, f)); return a; };
  const f = C.parseWindGrid(g, parts(one), parts(wave), 0);
  assert.equal(f.hours, hours);
  const w = C.windAt(f, -32, 115.7, 1800000);
  near(w.kn, 15, 1e-3); near(w.from, 270, 1e-3); near(w.gust, 20, 1e-3); assert.ok(w.u > 0);
  assert.equal(C.windAt(f, -40, 115.7, 0), null);
  const wv = C.wavesAt(f, -32, 115.7, 3600000);
  near(wv.wave, 2, 1e-6); assert.equal(wv.swellFrom, 240); assert.equal(C.wavesShort(wv), '1.0 / 1.5');
  assert.equal(C.windShort(w), '15 G20 W');
  assert.ok(C.windFits({ ...f, fetched: 0, t0: 0, hours: 200 }, 115.5, -32.2, 115.9, -31.9, true, 1000));
  assert.ok(!C.windFits({ ...f, fetched: 0, t0: 0, hours: 200 }, 118, -32.2, 118.4, -31.9, true, 1000));
  assert.equal(C.scaleBar(20, 96).label, '1 nm'); assert.equal(C.scaleBar(15, 96).label, '0.5 nm'); near(C.scaleBar(15, 96).px, 926 / 15, 0.1); assert.equal(C.scaleBar(0.5, 96).label, '20 m');
}
// AIS
{
  const vs = C.parseVessels({ features: [
    { geometry: { coordinates: [115.7, -32.1] }, properties: { kind: 'vessel', mmsi: 503583000, name: ' SVITZER ', seen: '2026-11-28T07:50:11Z', sog: 9.5, cog: 232.5, type: 52, length: 30, flag: 'AU' } },
    { geometry: { coordinates: [115.7, -32.1] }, properties: { kind: 'buoy', mmsi: 1, seen: '2026-11-28T07:50:11Z' } },
    { geometry: { coordinates: [115.7, -32.1] }, properties: { kind: 'vessel', mmsi: 2, seen: 'nonsense' } },
    { geometry: { coordinates: [115.7, -32.1] }, properties: { mmsi: 3, seen: '2026-11-28T07:50:11Z', sog: 102.3, heading: 511 } },
  ] });
  assert.equal(vs.length, 2);
  assert.equal(vs[0].name, 'SVITZER'); assert.equal(vs[0].seen, Date.UTC(2026, 10, 28, 7, 50, 11)); assert.equal(C.vesselType(vs[0].type), 'Tug');
  assert.ok(C.vesselMoving(vs[0])); assert.equal(vs[1].sog, null); assert.equal(vs[1].heading, null); assert.equal(C.vesselLabel(vs[1]), 'MMSI 3');
  assert.throws(() => C.parseVessels({}));
  const big = C.aisPad({ s: -40, w: 100, n: -20, e: 130 });
  assert.ok(big.n - big.s <= 9 + 1e-9 && big.e - big.w <= 9 + 1e-9);
  const box = C.aisPad({ s: -32.2, w: 115.5, n: -32, e: 115.8 });
  near(box.n - box.s, 0.3, 1e-9); assert.ok(C.aisContains(box, { s: -32.2, w: 115.5, n: -32, e: 115.8 })); assert.ok(!C.aisContains(box, { s: -33, w: 115.5, n: -32, e: 115.8 }));
}
console.log('planner tests passed');
