const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteSubtitlePolicy();

function item(name, size = 100) {
  return { name, path: `/${name}`, kind: 'other', contentType: 'text/plain', size, lastModified: '' };
}

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const video = { name: 'Movie.mp4', path: '/Movie.mp4', kind: 'video', contentType: 'video/mp4',
  size: 1000, lastModified: '' };
equal(policy.findSidecar([item('Movie.zh.srt'), item('Movie.srt'), item('Movie.vtt')], video).name,
  'Movie.vtt', 'Exact VTT priority');
equal(policy.findSidecars([item('Movie.zh.srt'), item('Movie.srt'), item('Movie.vtt')], video)
  .map(value => value.name), ['Movie.vtt', 'Movie.srt', 'Movie.zh.srt'], 'Stable track priority');
equal(policy.findSidecar([item('Movie.zh.srt'), item('Movie.en.srt')], video).name,
  'Movie.en.srt', 'Stable language sidecar order');
equal(policy.findSidecar([item('Movie.srt', 1024 * 1024 + 1)], video), undefined, 'Oversize rejection');
equal(policy.findSidecar([item('Other.srt'), item('Movie.txt')], video), undefined, 'Unrelated file rejection');
equal(policy.findSidecar([Object.assign(item('Movie.srt'), { size: NaN })], video), undefined,
  'Non-finite size rejection');
equal(policy.findSidecars(new Array(40).fill(null).map((_, index) => item(`Movie.${index}.srt`)), video).length,
  32, 'Subtitle track quota');

console.log('Remote subtitle policy tests passed');
