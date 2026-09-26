import test from 'node:test';
import assert from 'node:assert/strict';
import { localApiOrigin } from '../src/localProxy.js';
test('default and isolated backend origins stay distinct', () => {
  assert.equal(localApiOrigin(), 'http://127.0.0.1:10010');
  assert.equal(localApiOrigin('http://127.0.0.1:20010'), 'http://127.0.0.1:20010');
});
test('invalid or external proxy targets fail instead of falling back', () => {
  for (const value of ['', 'https://example.com', 'http://user:pass@127.0.0.1:20010',
    'http://127.0.0.1:20010/path', 'http://127.0.0.1:20010?x=1', 'http://127.0.0.1:65536']) {
    assert.throws(() => localApiOrigin(value), value);
  }
});
