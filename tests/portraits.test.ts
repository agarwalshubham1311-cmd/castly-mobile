import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { talents } from '../src/talent.ts';
const root = new URL('../', import.meta.url);
const sources = JSON.parse(readFileSync(new URL('assets/talent/sources.json', root), 'utf8')) as {
  file: string;
  source: string;
  sha256: string;
  license: string;
}[];
test('11 distinct local JPEGs match source hashes and license metadata', () => {
  assert.equal(sources.length, 11);
  assert.equal(new Set(sources.map((s) => s.sha256)).size, 11);
  for (const source of sources) {
    const file = readFileSync(new URL(source.file, root));
    assert.equal(file.subarray(0, 2).toString('hex'), 'ffd8');
    assert.equal(createHash('sha256').update(file).digest('hex'), source.sha256);
    assert.equal(source.license, 'Unsplash License');
    assert.ok(source.source.startsWith('https://images.unsplash.com/'));
  }
});
test('every talent has a bundled portrait and gallery; every photograph is used', () => {
  const registry = readFileSync(new URL('src/talentMedia.ts', root), 'utf8');
  const images = talents.flatMap((t) => {
    assert.ok(t.portfolio.length > 0);
    return [t.portrait, ...t.portfolio.map((p) => p.image)];
  });
  assert.equal(new Set(images).size, 11);
  for (const id of images) {
    assert.ok(registry.includes(`'${id}': require('../assets/talent/${id}.jpg')`));
    assert.ok(sources.some((s) => s.file.endsWith(`${id}.jpg`)));
  }
});
