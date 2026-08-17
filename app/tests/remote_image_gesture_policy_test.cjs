const policyModule = require(process.argv[2]);
const policy = new policyModule.RemoteImageGesturePolicy();

function equal(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected=${JSON.stringify(expected)}, actual=${JSON.stringify(actual)}`);
  }
}

equal(policy.clampScale(0), 1, 'Minimum scale');
equal(policy.clampScale(2.5), 2.5, 'Middle scale');
equal(policy.clampScale(8), 4, 'Maximum scale');
equal(policy.clampScale(NaN), 1, 'Invalid scale');
equal(policy.normalizeNavigationDirection(10), 1, 'Forward navigation normalization');
equal(policy.normalizeNavigationDirection(-4), -1, 'Backward navigation normalization');
equal(policy.normalizeNavigationDirection(Infinity), 0, 'Invalid navigation rejection');
equal(policy.nextIndex(4, 3, 1), 0, 'Forward navigation wrap');
equal(policy.nextIndex(4, 0, -1), 3, 'Backward navigation wrap');
equal(policy.nextIndex(1, 0, 1), -1, 'Single image rejection');
equal(policy.nextIndex(10001, 0, 1), -1, 'Oversize gallery rejection');
equal(policy.nextIndex(4, 4, 1), -1, 'Invalid current index rejection');
equal(policy.clampTranslation(100, -200, 1), { x: 0, y: 0 }, 'Original scale reset');
equal(policy.clampTranslation(9999, -9999, 2), { x: 360, y: -520 }, 'Translation bounds');
equal(policy.clampTranslation(NaN, Infinity, 2), { x: 0, y: 0 }, 'Invalid translation');
equal(policy.swipeDirection(-90, 10, 1), 1, 'Next image swipe');
equal(policy.swipeDirection(90, 10, 1), -1, 'Previous image swipe');
equal(policy.swipeDirection(70, 0, 1), 0, 'Short swipe rejection');
equal(policy.swipeDirection(100, 100, 1), 0, 'Vertical ambiguity rejection');
equal(policy.swipeDirection(-100, 0, 1.1), 0, 'Zoomed swipe rejection');
equal(policy.verticalSwipeDirection(10, -120, 1), 1, 'Next mixed media vertical swipe');
equal(policy.verticalSwipeDirection(10, 120, 1), -1, 'Previous mixed media vertical swipe');
equal(policy.verticalSwipeDirection(10, 79, 1), 0, 'Short vertical swipe rejection');
equal(policy.verticalSwipeDirection(100, 100, 1), 0, 'Diagonal vertical swipe rejection');
equal(policy.verticalSwipeDirection(0, -120, 1.1), 0, 'Zoomed vertical swipe rejection');

console.log('Remote image gesture policy tests passed');
