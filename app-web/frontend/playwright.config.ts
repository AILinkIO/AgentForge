import { defineConfig } from '@playwright/test'

// 端到端测试：对一个真在跑的 agentforge-web（真模型）跑。由 e2e/run-e2e.sh 起后端、建临时配置目录与工作根，
// 再带着 E2E_BASE_URL / E2E_ROOT 调 playwright。浏览器用本机装的 Google Chrome（channel: 'chrome'），不另外下载。
export default defineConfig({
  testDir: './e2e',
  outputDir: process.env.E2E_OUT ?? 'e2e-results',
  timeout: 300_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: process.env.E2E_REPORT ?? 'e2e-report', open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8787',
    channel: process.env.E2E_CHANNEL ?? 'chrome',
    viewport: { width: 1440, height: 900 },
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    actionTimeout: 15_000,
  },
})
