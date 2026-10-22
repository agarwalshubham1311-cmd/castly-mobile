import { test } from 'node:test';
import assert from 'node:assert/strict';
import { c } from '../src/theme.ts';
import { localDateISO, parseLocalDate, readableDate } from '../src/dates.ts';

function luminance(hex: string) {
  const values = hex
    .slice(1)
    .match(/../g)!
    .map((part) => {
      const value = parseInt(part, 16) / 255;
      return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
    });
  return values[0] * 0.2126 + values[1] * 0.7152 + values[2] * 0.0722;
}
test('normal text meets AA on actual Lavender Pearl surfaces without changing primary colors', () => {
  assert.equal(c.primary, '#8055E8');
  assert.equal(c.primaryDark, '#6330D1');
  const foregrounds: string[] = [c.text, c.secondary, c.primaryDark, c.error];
  for (const background of [
    c.white,
    c.background,
    c.lavender,
    '#F2ECFF',
    '#F7F4FC',
    c.peach,
    c.gold,
  ]) {
    for (const foreground of foregrounds) {
      const ratio: number = (luminance(background) + 0.05) / (luminance(foreground) + 0.05);
      assert.ok(ratio >= 4.5, `${foreground} on ${background}: ${ratio.toFixed(2)}:1`);
    }
  }
  assert.ok((luminance(c.white) + 0.05) / (luminance(c.primary) + 0.05) >= 4.5);
});
test('date selection preserves the device calendar day and rejects impossible dates', () => {
  const nearMidnight = new Date(2027, 0, 1, 0, 30);
  assert.equal(localDateISO(nearMidnight), '2027-01-01');
  for (const value of ['2028-02-29', '2027-01-01', '2027-12-31']) {
    assert.equal(localDateISO(parseLocalDate(value)!), value);
    assert.equal(parseLocalDate(value)!.getHours(), 12);
    assert.notEqual(readableDate(value), 'Choose a date');
  }
  for (const value of ['', '2027-02-29', '2027-02-30', '2027-13-01', '01/02/2027']) {
    assert.equal(parseLocalDate(value), null);
    assert.equal(readableDate(value), 'Choose a date');
  }
});
