import { defineConfig } from '@playwright/test';

/**
 * 培训模块冒烟测试配置。
 *
 * 前置：后端 8080 与前端 3000 已启动（仓库根目录 sh dev.sh），
 * 或设置 E2E_BASE_URL / E2E_API_URL 指向其它环境。
 * 登录账号通过 E2E_ACCOUNT / E2E_PASSWORD / E2E_COMPANY_ID 覆盖。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:3000',
    // 本机用系统 Chrome，避免下载浏览器；CI 可改为默认 chromium
    channel: process.env.E2E_CHANNEL || 'chrome',
    storageState: 'e2e/.auth/admin.json',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  globalSetup: './e2e/global-setup.ts',
  webServer: process.env.E2E_NO_WEBSERVER ? undefined : {
    command: 'npm run dev',
    url: process.env.E2E_BASE_URL || 'http://localhost:3000/login',
    reuseExistingServer: true,
    timeout: 180_000,
  },
});
