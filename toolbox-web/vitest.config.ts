import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 组件测试配置。
 *
 * **为什么与 `node:test` 并存**：仓库原有 649 项测试跑在 `node --test` 上，
 * 它们全是**纯逻辑测试**（`src/lib/*.ts` 的纯模块），不需要 DOM，也不碰 `.vue`。
 *
 * 组件测试需要两样它们给不了的东西：**DOM**（jsdom）与**编译 `.vue` 单文件组件**。
 * 与其把 649 项迁到新运行器（改动大、风险高、收益低），
 * 这里只让**组件测试**走 Vitest，`npm test` 保持原样。
 *
 * **边界写清楚**：`include` 只指向 `tests/component/`——
 * 别的目录不会被它捡走，两套测试不会互相干扰。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['tests/component/**/*.test.ts'],
    // 组件测试不参与覆盖率门槛：仓库的覆盖率口径针对纯逻辑模块。
    globals: false
  }
})
