const { RemoteMediaPlaybackUrlPolicy } = require(process.argv[2]);
const policy = new RemoteMediaPlaybackUrlPolicy();
const ticket = 'abcdef0123456789abcdef0123456789';
const paths = [
  `/api/app/v1/media/tickets/${ticket}`,
  `/api/app/v1/local-media/tickets/${ticket}`,
  `/api/app/v1/drive-tickets/${ticket}`,
  `/api/app/v1/audiobook-playback/tickets/${ticket}`,
  `/api/app/v1/audiobook-voice-preview/tickets/${ticket}`
];

function equal(actual, expected, message) {
  if (actual !== expected) throw new Error(`${message}: expected=${expected}, actual=${actual}`);
}

function rejects(value) {
  let rejected = false;
  try { policy.url(value); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`Unsafe playback URL accepted: ${JSON.stringify(value)}`);
}

for (const host of ['10.0.0.1', '172.16.0.1', '172.31.255.254', '192.168.3.159']) {
  for (const port of [23201]) {
    for (const path of paths) {
      const value = `http://${host}:${port}${path}`;
      equal(policy.url(value), value, 'Trusted LAN ticket playback');
    }
  }
}
for (const value of [
  'https://example.com/audio.mp3?signature=test#segment',
  `https://example.com${paths[0]}`
]) equal(policy.url(value), value, 'Existing HTTPS playback remains compatible');

for (const host of ['127.0.0.1', '172.15.255.254', '172.32.0.1', '8.8.8.8', 'example.com',
  '192.168.003.159', '999.1.1.1', '[::1]', 'user@192.168.3.159']) {
  rejects(`http://${host}:23201${paths[0]}`);
}
for (const port of ['', ':80', ':443', ':23110', ':23111', ':23200', ':23202']) {
  rejects(`http://192.168.3.159${port}${paths[0]}`);
}
const base = 'http://192.168.3.159:23201';
for (const value of [
  '', 'file:///audio.mp3', `HTTP://192.168.3.159:23201${paths[0]}`,
  `${base}${paths[0]}?download=1`, `${base}${paths[0]}#fragment`,
  `${base}${paths[0]}/extra`, `${base}${paths[0]}/`,
  `${base}${paths[0].replace('/tickets/', '//tickets/')}`,
  `${base}${paths[0].replace('/tickets/', '/other/../tickets/')}`,
  `${base}${paths[0].replace('/tickets/', '/%74ickets/')}`,
  `${base}${paths[0].replace(ticket, ticket.toUpperCase())}`,
  `${base}${paths[0].replace(ticket, ticket.slice(1))}`,
  `${base}${paths[0].replace(ticket, `${ticket}a`)}`,
  `${base}/api/app/v1/audiobook-export/tickets/${ticket}`,
  `${base}/api/app/v1/media/items/${ticket}`, `${base}/audio.mp3`,
  `${base} ${paths[0]}`, `${base}\t${paths[0]}`, `${base}\n${paths[0]}`,
  ` ${base}${paths[0]}`, `${base}${paths[0]} `, `${base}${paths[0]}\n`,
  `${base}${paths[0]}\r`, `${base}${paths[0]}\u0000`,
  `http://192.168.3.159:23201\\example.com${paths[0]}`
]) rejects(value);

const { HomeChannelOrigin } = require(require('path').join(require('path').dirname(process.argv[2]), '../connectivity/HomeChannelOrigin.js'));
const privateBase = HomeChannelOrigin.open(32101, '01'.repeat(16), 'https://example.test');
const channelTicket = privateBase + paths[0];
if (HomeChannelOrigin.forService('https://example.test')) throw Error('Unverified channel was published');
HomeChannelOrigin.activate(privateBase);
if (policy.url(channelTicket) !== channelTicket || !HomeChannelOrigin.forService('https://example.test')) throw Error('Active channel unavailable');
rejects(channelTicket.replace('32101', '32102'));
rejects(channelTicket.replace('01'.repeat(16), '02'.repeat(16)));
rejects(privateBase + '/api/app/v1/identity/me');
HomeChannelOrigin.close();
rejects(channelTicket);
console.log('Remote media playback URL policy tests passed');
