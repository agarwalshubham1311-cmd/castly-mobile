import { test } from 'node:test';
import assert from 'node:assert/strict';
import { defaultFilters, findTalents, talents } from '../src/talent.ts';
test('discovery returns the full catalog without constraints', () => assert.equal(findTalents('', 'All talent', defaultFilters).length, talents.length));
test('search matches skills and trims case-insensitively', () => assert.deepEqual(findTalents('  GUITAR ', 'All talent', defaultFilters).map(t => t.id), ['ishaan']));
test('category, location, budget and availability combine', () => {
 assert.deepEqual(findTalents('', 'Actors', { city: 'Mumbai', maxRate: 20000, availableOnly: true }).map(t => t.id), ['aria']);
 assert.equal(findTalents('', 'Actors', { city: 'Delhi', maxRate: 20000, availableOnly: true }).length, 0);
 assert.equal(findTalents('', 'All talent', { ...defaultFilters, maxRate: 15000 }).length, 2);
 assert.equal(findTalents('', 'Creators', { ...defaultFilters, availableOnly: true }).length, 0);
});
test('unknown searches have no results', () => assert.equal(findTalents('nonexistent', 'All talent', defaultFilters).length, 0));
