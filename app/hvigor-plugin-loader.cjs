const fs = require('fs');
const Module = require('module');
const path = require('path');

function resolvePluginHome() {
  const candidates = [
    process.env.DEVECO_HVIGOR_OHOS_PLUGIN_HOME,
    '/Applications/DevEco-Studio.app/Contents/tools/hvigor/hvigor-ohos-plugin'
  ].filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(path.join(candidate, 'package.json')));
}

function loadOhosPlugin() {
  const pluginHome = resolvePluginHome();
  if (!pluginHome) {
    throw new Error('Cannot locate the DevEco hvigor HarmonyOS plugin.');
  }
  const bundledSdkHome = path.resolve(pluginHome, '..', '..', '..', 'sdk');
  const sdkHome = process.env.DEVECO_SDK_HOME || bundledSdkHome;
  process.env.DEVECO_SDK_HOME = sdkHome;
  process.env.HOS_SDK_HOME = sdkHome;
  process.env.OHOS_BASE_SDK_HOME = path.join(sdkHome, 'default', 'openharmony');
  process.env.OHOS_SDK_HOME = process.env.OHOS_BASE_SDK_HOME;
  const hvigorHome = path.join(path.dirname(pluginHome), 'hvigor');
  const aliases = {
    '@ohos/hvigor': hvigorHome,
    '@ohos/hvigor-ohos-plugin': pluginHome
  };
  const originalResolveFilename = Module._resolveFilename;
  Module._resolveFilename = function resolveWithAliases(request, parent, isMain, options) {
    if (aliases[request]) {
      return originalResolveFilename.call(this, aliases[request], parent, isMain, options);
    }
    return originalResolveFilename.call(this, request, parent, isMain, options);
  };
  return require('@ohos/hvigor-ohos-plugin');
}

module.exports = { loadOhosPlugin };
