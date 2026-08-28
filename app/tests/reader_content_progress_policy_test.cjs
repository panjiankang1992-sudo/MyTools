const moduleUnderTest = require(process.argv[2]);
const policy = new moduleUnderTest.ReaderContentProgressPolicy();

const chapters = [
  { title: '短章', content: 'a'.repeat(100) },
  { title: '长章', content: 'b'.repeat(900) }
];
const afterShort = policy.percentage(chapters, 1, 0);
if (Math.abs(afterShort - 10) > 0.001) throw new Error(`Content weighted progress expected 10, got ${afterShort}`);
const middleLong = policy.percentage(chapters, 1, 0.5);
if (Math.abs(middleLong - 55) > 0.001) throw new Error(`Chapter fraction expected 55, got ${middleLong}`);

const restored = policy.location(chapters, 55);
if (restored.chapterIndex !== 1 || Math.abs(restored.chapterFraction - 0.5) > 0.001) {
  throw new Error(`Content weighted location mismatch: ${JSON.stringify(restored)}`);
}

const partiallyLoaded = [
  { title: '一', content: 'a'.repeat(200) },
  { title: '二', content: '' },
  { title: '三', content: '' }
];
const estimated = policy.percentage(partiallyLoaded, 1, 0);
if (Math.abs(estimated - 100 / 3) > 0.001) throw new Error(`Unloaded chapter estimate mismatch: ${estimated}`);
if (policy.percentage([], 0, 0) !== 0) throw new Error('Empty book progress');

console.log('Reader content progress policy tests passed');
