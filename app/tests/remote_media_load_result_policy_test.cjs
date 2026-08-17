const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.RemoteMediaLoadResultPolicy();

const item = (kind, id) => ({
  id: `${id}`,
  name: `${kind}-${id}`,
  path: `/local/${id}`,
  kind,
  contentType: `${kind}/test`,
  size: 1,
  lastModified: ''
});

let result = policy.resolve([item('video', 1), item('video', 2)], 501, 40, 'audio');
if (!result.inconsistent || result.mediaItems.length !== 0 || result.total !== 0 || result.hasMore) {
  throw new Error('Stale video result must not overwrite the audio filter');
}

result = policy.resolve([item('video', 1), item('video', 2)], 501, 40, 'video');
if (result.inconsistent || result.mediaItems.length !== 2 || result.total !== 501 || !result.hasMore) {
  throw new Error('Matching video result must retain server pagination');
}

result = policy.resolve([], 0, 40, 'audio');
if (result.inconsistent || result.total !== 0 || result.hasMore) {
  throw new Error('Empty audio result must remain a stable empty state');
}

result = policy.resolve([item('image', 1), item('video', 2), item('other', 3)], 3, 40, 'all');
if (result.inconsistent || result.mediaItems.length !== 2 || result.total !== 3 || result.hasMore) {
  throw new Error('All-media result must preserve supported mixed media');
}

console.log('Remote media load result policy tests passed');
