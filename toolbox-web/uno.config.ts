import { defineConfig, presetWind3 } from 'unocss';

/** 沿用现有站点的原子样式系统。 */
export default defineConfig({ presets: [presetWind3()], shortcuts: { 'card-wrapper': 'rd-8px shadow-sm' } });
