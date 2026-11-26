// node probe_chart.mjs wa.pmtiles lat,lon [lat,lon ...]: print depth, land and source at points, as the planner reads them.
import fs from 'node:fs';
import vm from 'node:vm';

const web = new URL('../../web/', import.meta.url);
vm.runInThisContext(fs.readFileSync(new URL('src/core.js', web), 'utf8'));
const pmtiles = vm.runInThisContext(`${fs.readFileSync(new URL('vendor/pmtiles.js', web), 'utf8')};pmtiles`);
const [chart, ...pts] = process.argv.slice(2);
const fd = fs.openSync(chart, 'r');
const archive = new pmtiles.PMTiles({ getKey: () => chart, async getBytes(offset, length) {
  const b = Buffer.alloc(length);
  fs.readSync(fd, b, 0, length, offset);
  return { data: b.buffer.slice(b.byteOffset, b.byteOffset + length) };
} });
const header = await archive.getHeader();
const C = globalThis.ChompCore;
const reader = new C.ChartReader(async (z, x, y) => { const t = await archive.getZxy(z, x, y); return t && t.data; }, header.maxZoom,
  ((await archive.getMetadata()).vector_layers || []).some((l) => l.id === 'source'));
for (const s of pts) {
  const [lat, lon] = s.split(',').map(Number);
  const r = await reader.at({ lat, lon });
  console.log(`${s}  ${C.depthText(r)}\n   ${C.sourceLines(r.source).join('\n   ') || '(no source)'}`);
}
