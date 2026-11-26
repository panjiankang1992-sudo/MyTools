import assert from 'node:assert/strict';
import test from 'node:test';
import { fetchTextbookCatalog } from '../src/services/curriculum-api';

test('catalog reads bypass cached responses with historical colliding validators', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (input, init) => {
      assert.equal(String(input), '/public/v1/textbooks?subject=english');
      assert.equal(init?.cache, 'no-store');
      return new Response(JSON.stringify({ code: 'OK', data: { catalogVersion: 'catalog-6', editions: [], coverage: [] } }));
    };
    assert.equal((await fetchTextbookCatalog({ subject: 'english' })).catalogVersion, 'catalog-6');
  } finally {
    globalThis.fetch = original;
  }
});
