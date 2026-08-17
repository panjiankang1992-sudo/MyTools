const parserModule = require(process.argv[2]);
const parser = new parserModule.RemoteSubtitleParser();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

function rejected(action, message) {
  let failed = false;
  try { action(); } catch (_) { failed = true; }
  equal(failed, true, message);
}

const srt = `1\r\n00:00:01,000 --> 00:00:03,500\r\n<b>Hello</b> &amp; world\r\n\r\n` +
  `2\r\n00:00:03,500 --> 00:00:05,000\r\nSecond line`;
const srtCues = parser.parse(srt);
equal(srtCues.length, 2, 'SRT cue count');
equal(srtCues[0].text, 'Hello & world', 'SRT markup sanitization');
equal(parser.textAt(srtCues, 2000), 'Hello & world', 'SRT active cue');
equal(parser.textAt(srtCues, 3500), 'Second line', 'SRT end boundary is exclusive');

const vtt = `WEBVTT\n\nNOTE ignored\nmetadata\n\ncue-1\n00:01.000 --> 00:02.250 align:center\nLine<br>two`;
const vttCues = parser.parse(vtt);
equal(vttCues[0], { startMs: 1000, endMs: 2250, text: 'Line\ntwo' }, 'WebVTT cue and settings');

const overlapping = parser.parse(`00:00:01.000 --> 00:00:04.000\nA\n\n00:00:02.000 --> 00:00:03.000\nB`);
equal(parser.textAt(overlapping, 2500), 'A\nB', 'Overlapping cue projection');
equal(parser.textAt(overlapping, -1), '', 'Invalid playback time');

rejected(() => parser.parse('not a subtitle'), 'Missing timeline rejection');
rejected(() => parser.parse(`00:00:02,000 --> 00:00:01,000\nbackwards`), 'Backwards timeline rejection');
rejected(() => parser.parse(`00:00:01,000 --> 00:00:02,000\n${'x'.repeat(2001)}`), 'Cue text quota');
rejected(() => parser.parse('x'.repeat(1024 * 1024 + 1)), 'Subtitle size quota');

console.log('Remote subtitle parser tests passed');
