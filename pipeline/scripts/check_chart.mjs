// node check_chart.mjs wa.pmtiles points.json: compare a finished chart with the source grid.
// Uses the planner's own chart reader. Exits 1 if any depth band is badly wrong.
import fs from 'node:fs';
import vm from 'node:vm';

const web = new URL('../../web/', import.meta.url);
vm.runInThisContext(fs.readFileSync(new URL('src/core.js', web), 'utf8'));
const pmtiles = vm.runInThisContext(`${fs.readFileSync(new URL('vendor/pmtiles.js', web), 'utf8')};pmtiles`);
const [chart, points] = process.argv.slice(2);
const fd = fs.openSync(chart, 'r');
const archive = new pmtiles.PMTiles({ getKey: () => chart, async getBytes(offset, length) {
  const b = Buffer.alloc(length);
  fs.readSync(fd, b, 0, length, offset);
  return { data: b.buffer.slice(b.byteOffset, b.byteOffset + length) };
} });
const header = await archive.getHeader();
const reader = new globalThis.ChompCore.ChartReader(async (z, x, y) => { const t = await archive.getZxy(z, x, y); return t && t.data; }, header.maxZoom,
  ((await archive.getMetadata()).vector_layers || []).some((l) => l.id === 'source'));

const bands = {};
let sea = 0, sourced = 0;
for (const [lon, lat, elev, band] of JSON.parse(fs.readFileSync(points, 'utf8'))) {
  const r = await reader.at({ lat, lon });
  if (elev < -5) { sea++; if (r.source && r.source.src) sourced++; }
  const b = bands[band] ??= { n: 0, land: 0, good: 0 };
  b.n++;
  if (r.land) b.land++;
  else if (r.depth) {
    // Contours are smoothed, so allow for the edges of a band.
    const tol = Math.max(3, -elev * 0.15);
    if (-elev >= r.depth[0] - tol && -elev <= r.depth[1] + tol) b.good++;
  }
}
let failed = false;
for (const [band, b] of Object.entries(bands)) {
  const top = Number(band.split('..')[1]), bottom = Number(band.split('..')[0]);
  const rule = top <= -5 ? ['depth right', b.good / b.n, 0.97] : top <= -2 ? ['depth right', b.good / b.n, 0.9]
    : bottom >= 2 ? ['land', b.land / b.n, 0.97] : null;   // the shoreline itself is fuzzy
  const ok = !rule || rule[1] >= rule[2];
  if (!ok) failed = true;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${band.padEnd(14)} ${b.n} points, land ${b.land}, depth right ${b.good}`);
}
// Every depth should say where it came from (the "source" layer).
console.log(`${sea && sourced / sea >= 0.99 ? 'ok  ' : 'FAIL'} source         ${sea} sea points, ${sourced} with a source`);
if (!sea || sourced / sea < 0.99) failed = true;
if (failed || Object.keys(bands).length < 10) { console.error('Chart does not match the source grid'); process.exit(1); }
