const normalizerModule = require(process.argv[2]);
const normalizer = new normalizerModule.ProfileResponseNormalizer();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

const photo = `data:image/jpeg;base64,${'A'.repeat(32 * 1024)}`;
equal(normalizer.normalize({ nickname: 'Reader', avatar: photo }), { nickname: 'Reader', avatar: photo },
  'Bounded JPEG data avatar');
equal(normalizer.normalize({ nickname: 'Reader', avatar: '/avatars/reader.webp' }).avatar,
  '/avatars/reader.webp', 'Relative avatar');
equal(normalizer.normalize({ nickname: 'Reader', avatar: 'https://example.com/avatar.png' }).avatar,
  'https://example.com/avatar.png', 'HTTPS avatar');
equal(normalizer.normalize({ nickname: 'Reader',
  avatar: `data:image/jpeg;base64,${'A'.repeat(513 * 1024)}` }).avatar, '', 'Oversized avatar fallback');
equal(normalizer.normalize({ nickname: 'Reader', avatar: 'data:image/svg+xml;base64,PHN2Zz4=' }).avatar, '',
  'SVG avatar fallback');
equal(normalizer.normalize({ nickname: 'Reader', avatar: 'http://example.com/avatar.png' }).avatar, '',
  'Insecure avatar fallback');
equal(normalizer.normalize({ nickname: 'Reader', avatar: 'javascript:alert(1)' }).avatar, '',
  'Unsafe avatar fallback');

console.log('Profile response normalizer tests passed');
