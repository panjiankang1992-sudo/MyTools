const assert = require('node:assert/strict');

module.exports = function run(SourceCookieSessionPolicy) {
  const policy = new SourceCookieSessionPolicy();
  assert.equal(policy.normalize('sid=abc; Path=/; HttpOnly; SameSite=Lax'), 'sid=abc');
  assert.equal(policy.normalize('sid=abc; theme=dark; Secure'), 'sid=abc; theme=dark');
  assert.equal(policy.normalize('sid=old; sid=new'), 'sid=new');
  assert.equal(policy.normalize('bad cookie=value; ok=yes'), 'ok=yes');
  assert.equal(policy.normalize('sid=with space'), '');
  assert.equal(policy.normalize('sid=abc\r\nInjected=yes'), '');
  assert.equal(policy.normalize(`sid=${'a'.repeat(1025)}`), '');
  assert.equal(policy.normalize('Path=/; Secure; HttpOnly'), '');
  const many = Array.from({ length: 33 }, (_, index) => `c${index}=v`).join('; ');
  assert.equal(policy.normalize(many).split('; ').length, 32);
  assert.equal(policy.merge('sid=old; theme=dark', 'sid=new; Path=/'), 'sid=new; theme=dark');
  assert.equal(policy.merge('sid=old; theme=dark', 'token=next'), 'sid=old; theme=dark; token=next');
  assert.equal(policy.merge('sid=old; theme=dark', 'sid='), 'theme=dark');
  assert.equal(policy.merge('sid=old; theme=dark', 'sid=old; Max-Age=0'), 'theme=dark');
  assert.equal(policy.merge('sid=old', 'Path=/; Secure'), 'sid=old');
  assert.equal(policy.merge('sid=old', 'bad\r\nInjected=yes'), 'sid=old');
};
