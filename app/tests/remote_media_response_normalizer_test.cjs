const moduleUnderTest = require(process.argv[2]);
const normalizer = new moduleUnderTest.RemoteMediaResponseNormalizer();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const sources = normalizer.normalizeSources([
  { id: 1, name: 'NAS', type: 'nextcloud', url: 'https://nas.example.com', isDefault: 1, isActive: 1 },
  { id: 1, name: 'NAS latest', type: 'nextcloud', url: 'https://nas.example.com', isDefault: 0, isActive: true },
  { id: '../2', name: 'Bad', type: 'alist', url: 'https://bad.example.com', isActive: 1 },
  { id: 3, name: 'Disabled', type: 'alist', url: 'https://a.example.com', isActive: 0 }
]);
equal(sources.length, 1, 'Sources should deduplicate and reject invalid or disabled records');
equal(sources[0].name, 'NAS latest', 'Last valid source version should win');
equal(sources[0].isDefault, false, 'Boolean-like integer flags should normalize');

const directory = normalizer.normalizeDirectory({ path: '/media', items: [
  { name: 'Movie.MP4', path: '/media/Movie.MP4', isDirectory: false, size: 123.9,
    contentType: 'application/octet-stream', lastModified: '2026-01-01T00:00:00Z' },
  { name: 'Album', path: '/media/Album', isDirectory: true, size: 0, contentType: '', lastModified: '' },
  { name: '../bad', path: '/media/bad', isDirectory: false, size: 1, contentType: '', lastModified: '' },
  { name: 'nan.bin', path: '/media/nan.bin', isDirectory: false, size: NaN, contentType: '', lastModified: '' },
  { name: 'relative', path: 'relative', isDirectory: false, size: 1, contentType: '', lastModified: '' }
] }, '/');
equal(directory.path, '/media', 'Directory path');
equal(directory.items.length, 2, 'Invalid directory records should be isolated');
equal(directory.items[0].kind, 'video', 'Extension media classification should be case insensitive');
equal(directory.items[0].size, 123, 'File size should normalize to integer');
equal(directory.items[1].kind, 'directory', 'Directory classification');

const unsafePaths = normalizer.normalizeDirectory({ path: '/media/', items: [
  { name: 'up.mp3', path: '/media/../up.mp3', isDirectory: false, size: 1, contentType: '', lastModified: '' },
  { name: 'dot.mp3', path: '/media/./dot.mp3', isDirectory: false, size: 1, contentType: '', lastModified: '' },
  { name: 'double.mp3', path: '/media//double.mp3', isDirectory: false, size: 1, contentType: '', lastModified: '' }
] }, '/');
equal(unsafePaths.path, '/media', 'Directory trailing slash should normalize');
equal(unsafePaths.items.length, 0, 'Traversal and duplicate separators should be rejected');

const duplicate = normalizer.normalizeDirectory({ path: '/x', items: [
  { name: 'old.mp3', path: '/x/a', isDirectory: false, size: 1, contentType: '', lastModified: '' },
  { name: 'new.mp3', path: '/x/a', isDirectory: false, size: 2, contentType: '', lastModified: '' }
] }, '/');
equal(duplicate.items.length, 1, 'Directory paths should deduplicate');
equal(duplicate.items[0].name, 'new.mp3', 'Last valid path version should win');

const manyItems = new Array(10020).fill(null).map((_, index) => ({ name: `${index}.mp3`, path: `/m/${index}`,
  isDirectory: false, size: index, contentType: 'audio/mpeg', lastModified: '' }));
equal(normalizer.normalizeDirectory({ path: '/m', items: manyItems }, '/').items.length, 10000,
  'Directory item quota');
equal(normalizer.normalizeDirectory({ path: 'bad', items: [] }, '/fallback').path, '/fallback',
  'Invalid response path should use requested path');

const now = Date.parse('2026-08-12T00:00:00Z');
const ticket = '0123456789abcdef0123456789abcdef';
const playback = normalizer.normalizePlayback({ ticket,
  streamPath: `/api/app/v1/media/tickets/${ticket}`, expiresAt: '2026-08-12T02:00:00Z' },
  'https://mytools.example.com', 'alist', now);
equal(playback.url, `https://mytools.example.com/api/app/v1/media/tickets/${ticket}`, 'Playback URL');
equal(playback.expiresAt, Date.parse('2026-08-12T02:00:00Z'), 'Playback expiry');
const localPlayback = normalizer.normalizePlayback({ ticket,
  streamPath: `/api/app/v1/local-media/tickets/${ticket}`, expiresAt: '2026-08-12T02:00:00Z' },
  'https://mytools.example.com', 'MYTOOLS_LOCAL', now);
equal(localPlayback.url, `https://mytools.example.com/api/app/v1/local-media/tickets/${ticket}`,
  'Local playback URL');

const localSources = normalizer.normalizeLocalSources([
  { id: 7, directoryName: 'MyTools媒体库', directoryPath: '/opt/media', directoryType: 'MULTIMEDIA' },
  { id: 9, directoryName: 'Large Media', directoryPath: '/opt/big_media', directoryType: 'LARGE_MEDIA' },
  { id: 8, directoryName: '电子书', directoryType: 'EBOOK' }
]);
equal(localSources.length, 2, 'Local remote file source count');
equal(localSources[0].id, 'local:7', 'Local multimedia source ID');
equal(localSources[0].name, '远程文件', 'Local multimedia display name');
equal(localSources[0].url, '/opt/media', 'Local multimedia root path');
equal(localSources[0].isDefault, true, 'Local multimedia source should remain default');
equal(localSources[1].id, 'local:9', 'Local large media source ID');
equal(localSources[1].name, '大文件', 'Local large media display name');
equal(localSources[1].localDirectoryType, 'LARGE_MEDIA', 'Local large media source type');
const localBookSources = normalizer.normalizeLocalBookSources([
  { id: 7, directoryName: 'MyTools媒体库', directoryPath: '/opt/media', directoryType: 'MULTIMEDIA' },
  { id: 8, directoryName: '电子书', directoryPath: '/opt/ebooks', directoryType: 'EBOOK' },
  { id: 9, directoryName: '备用电子书', directoryPath: '/opt/books', directoryType: 'EBOOK' }
]);
equal(localBookSources.length, 2, 'Local ebook source count');
equal(localBookSources[0].id, 'local:8', 'Local ebook source ID');
equal(localBookSources[0].name, 'MyTools 电子书', 'Local ebook display name');
equal(localBookSources[0].url, '/opt/ebooks', 'Local ebook root path');
equal(localBookSources[0].localDirectoryType, 'EBOOK', 'Local ebook directory type');
equal(localBookSources[0].isDefault, true, 'First local ebook source should be default');
equal(localBookSources[1].isDefault, false, 'Only one local ebook source should be default');
const localFiles = normalizer.normalizeLocalFiles({ total: '10472', list: [
  { id: 91, filename: 'photo.jpg', filePath: '/opt/media/20260814/trip/photo.jpg', fileSize: 123,
    mimeType: 'image/jpeg', updateTime: '2026-08-14T10:00:00',
    tags: [{ tagName: '旅行', tagType: 'topic', confidence: 0.98 },
      { tagName: '风景', tagType: 'scene', confidence: 0.91 }] },
  { id: 92, filename: 'movie.mp4', filePath: '/opt/media/movie.mp4', fileSize: 456,
    mimeType: 'video/mp4', updateTime: '2026-08-14T10:00:00' }
] }, '/opt/media');
equal(localFiles.items.map(item => item.kind), ['image', 'video'], 'Local media kinds');
equal(localFiles.items[1].path, '/local/92', 'Local media opaque path');
equal(localFiles.items[0].tags.map(tag => tag.name), ['旅行', '风景'], 'Local media multiple tags');
equal(localFiles.items.map(item => item.directoryName), ['20260814/trip', '根目录'],
  'Local media directory grouping');

const filters = normalizer.normalizeLocalFilters({
  directories: ['', '20260814/trip', '20260814/trip', '../invalid', '/absolute'],
  tags: ['旅行', '风景', '旅行', '', 'x'.repeat(33)]
});
equal(filters.directories, ['根目录', '20260814/trip'], 'Local media directory filter options');
equal(filters.tags, ['旅行', '风景'], 'Local media tag filter options');
equal([localFiles.total, localFiles.page, localFiles.pageSize], [10472, 1, 24], 'Local media pagination');
const localFilesWithoutTotal = normalizer.normalizeLocalFiles({ list: [
  { id: 93, filename: 'fallback.jpg', filePath: '/opt/media/fallback.jpg', fileSize: 12,
    mimeType: 'image/jpeg', updateTime: '2026-08-14T10:00:00' }
] }, '/opt/media');
equal(localFilesWithoutTotal.total, undefined, 'Missing server total should remain unknown for page-size fallback');

let rejected = false;
try {
  normalizer.normalizePlayback({ ticket, streamPath: `/api/app/v1/media/tickets/${ticket}extra`,
    expiresAt: '2026-08-12T02:00:00Z' }, 'https://mytools.example.com', 'alist', now);
} catch (_) { rejected = true; }
equal(rejected, true, 'Mismatched playback path rejection');
rejected = false;
try {
  normalizer.normalizePlayback({ ticket, streamPath: `/api/app/v1/media/tickets/${ticket}`,
    expiresAt: '2026-08-11T20:00:00Z' }, 'https://mytools.example.com', 'alist', now);
} catch (_) { rejected = true; }
equal(rejected, true, 'Expired playback ticket rejection');

equal(normalizer.normalizeMetrics({ transferredBytes: 1024, activeStreams: 2, lastTransferTime: 100 }),
  { transferredBytes: 1024, activeStreams: 2, lastTransferTime: 100 }, 'Playback metrics');
rejected = false;
try { normalizer.normalizeMetrics({ transferredBytes: -1, activeStreams: NaN, lastTransferTime: 0 }); }
catch (_) { rejected = true; }
equal(rejected, true, 'Invalid playback metrics rejection');

console.log('Remote media response normalizer tests passed');
