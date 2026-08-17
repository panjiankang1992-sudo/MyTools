const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.TrustedLanEndpointPolicy();

for (const address of ['10.0.0.1', '172.16.0.1', '172.31.255.254', '192.168.3.159']) {
  if (!policy.isPrivateIpv4(address)) throw new Error(`Private IPv4 rejected: ${address}`);
}
for (const address of ['127.0.0.1', '172.15.0.1', '172.32.0.1', '8.8.8.8', '224.0.0.251', '999.1.1.1',
  '192.168.003.159']) {
  if (policy.isPrivateIpv4(address)) throw new Error(`Unsafe IPv4 accepted: ${address}`);
}
if (policy.normalize('http://192.168.3.159:23110/') !== 'http://192.168.3.159:23110') {
  throw new Error('LAN endpoint normalization failed');
}
for (const endpoint of ['https://192.168.3.159:23110', 'http://192.168.3.159:80',
  'http://8.8.8.8:23110', 'http://192.168.3.159:23110/api', 'http://user@192.168.3.159:23110']) {
  let rejected = false;
  try { policy.normalize(endpoint); } catch (_) { rejected = true; }
  if (!rejected) throw new Error(`Unsafe endpoint accepted: ${endpoint}`);
}
console.log('Trusted LAN endpoint policy tests passed');
