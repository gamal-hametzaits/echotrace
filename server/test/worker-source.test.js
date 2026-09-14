import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../worker.js', import.meta.url), 'utf8');
test('connect upserts the caller before looking up its partner', () => {
  assert.match(source, /await k\.put\('dev:'\+m/);
  assert.ok(source.indexOf("k.put('dev:'+m") < source.indexOf("k.get('dev:'+a"));
});
test('missing partner becomes a pending rendezvous instead of a terminal 404', () => {
  assert.match(source, /k\.put\('pending:'\+m/);
  assert.match(source, /\{ok:true,pending:true\}/);
  assert.doesNotMatch(source, /partner code not found/);
});
test('reciprocal pending requests create both directional connections', () => {
  assert.match(source, /pending\?\.partner===m/);
  assert.match(source, /k\.put\('conn:'\+a/);
  assert.match(source, /pairedBoth:true/);
});
