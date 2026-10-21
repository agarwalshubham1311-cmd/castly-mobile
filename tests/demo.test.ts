import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateBooking, parseSaved } from '../src/demo.ts';
import { searchTalents, defaultFilters, talents } from '../src/talent.ts';
const valid = {
  project: 'Brand photoshoot',
  date: '2027-02-20',
  location: 'Mumbai',
  requirements: 'An editorial campaign with three finished portraits.',
  budget: '18000',
};
test('booking validation accepts a complete future local request', () =>
  assert.deepEqual(validateBooking(valid, '2026-10-21'), {}));
test('booking validation rejects impossible dates, past dates and incomplete fields', () => {
  for (const date of ['2027-02-30', '2026-01-01', 'not-a-date'])
    assert.ok(validateBooking({ ...valid, date }, '2026-10-21').date);
  assert.equal(
    Object.keys(
      validateBooking(
        { project: '', date: '', location: '', requirements: '', budget: '-1' },
        '2026-10-21',
      ),
    ).length,
    5,
  );
  assert.ok(validateBooking({ ...valid, budget: 'Infinity' }, '2026-10-21').budget);
});
test('saved restoration deduplicates and removes obsolete or malformed IDs', () => {
  assert.deepEqual(
    parseSaved(
      '["aria","aria","obsolete",null,2]',
      talents.map((t) => t.id),
    ),
    ['aria'],
  );
  assert.deepEqual(parseSaved('{}', ['aria']), []);
  assert.deepEqual(parseSaved(null, ['aria']), []);
  assert.throws(() => parseSaved('invalid JSON', ['aria']));
});
test('search applies minimum budget and skills while sorting without mutating fixtures', () => {
  const before = talents.map((t) => t.id);
  assert.deepEqual(
    searchTalents(
      '',
      'All talent',
      { ...defaultFilters, minRate: 16000, skills: ['Guitar'] },
      'Price: low to high',
    ).map((t) => t.id),
    ['ishaan'],
  );
  assert.deepEqual(
    searchTalents('', 'All talent', defaultFilters, 'Price: low to high').map((t) => t.rate),
    [12000, 15000, 18000, 22000],
  );
  assert.deepEqual(
    talents.map((t) => t.id),
    before,
  );
});
