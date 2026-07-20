const { chromium } = require('@playwright/test');
const BASE = 'http://localhost:3000';
const API = 'http://localhost:8080/api/v1';

async function login(page, account = 'admin', password = '123456') {
  await page.goto(`${BASE}/login`);
  await page.fill('input[type="text"]', 'c0000000-0000-0000-0000-000000000001');
  // Find account input (2nd text input)
  const inputs = await page.locator('input[type="text"]');
  if (await inputs.count() > 1) await inputs.nth(1).fill(account);
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForTimeout(2000);
}

async function testButton(page, selector, description) {
  try {
    const btn = page.locator(selector).first();
    if (await btn.isVisible({ timeout: 2000 })) {
      await btn.click();
      await page.waitForTimeout(500);
      const url = page.url();
      console.log(`  ✅ ${description} → ${url.substring(0, 60)}`);
      return true;
    }
    console.log(`  ⚠️ ${description} → 不可见`);
    return false;
  } catch(e) {
    console.log(`  ❌ ${description} → ${e.message.substring(0, 80)}`);
    return false;
  }
}

async function run() {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  let pass = 0, fail = 0;

  // === A1 Login ===
  console.log('\n=== A1 登录页 ===');
  await login(page, 'admin');
  if (page.url().includes('/login')) { console.log('  ❌ 登录失败'); fail++; }
  else { console.log('  ✅ 登录成功'); pass++; }

  // === A2 首页 ===
  console.log('\n=== A2 工作台 ===');
  await page.goto(BASE);
  await page.waitForTimeout(1000);
  const btns = [
    { sel: 'button:has-text("我的空间")', desc: '我的空间卡片' },
    { sel: 'button:has-text("经验广场")', desc: '经验广场卡片' },
    { sel: 'button:has-text("工具箱")', desc: '工具箱卡片' },
    { sel: 'button:has-text("继续")', desc: '继续访谈' },
    { sel: 'button:has-text("查看报告")', desc: '查看报告' },
  ];
  for (const b of btns) {
    if (await testButton(page, b.sel, b.desc)) pass++; else fail++;
    await page.goto(BASE);
    await page.waitForTimeout(500);
  }

  // === B2 创建访谈 ===
  console.log('\n=== B2 创建新访谈 ===');
  await page.goto(`${BASE}/interview/create`);
  await page.waitForTimeout(1000);
  const b2btns = [
    { sel: 'input[type="radio"]', desc: '预设主题单选' },
    { sel: 'button:has-text("自定义主题")', desc: '自定义主题入口' },
    { sel: 'button:has-text("开始访谈")', desc: '开始访谈按钮' },
    { sel: 'button:has-text("返回")', desc: '返回按钮' },
  ];
  for (const b of b2btns) {
    if (await testButton(page, b.sel, b.desc)) pass++; else fail++;
  }

  // === C4 技能对话 ===
  console.log('\n=== C4 AI分身对话 ===');
  await page.goto(`${BASE}/skill/60000000-0000-0000-0000-000000000001`);
  await page.waitForTimeout(2000);
  const c4btns = [
    { sel: 'button:has-text("问答")', desc: '问答标签' },
    { sel: 'button:has-text("对练")', desc: '对练标签' },
    { sel: 'textarea', desc: '输入框(可聚焦)' },
    { sel: 'button:has-text("破冰")', desc: '(需要可见的快捷标签)' },
  ];
  for (const b of c4btns) {
    try {
      const el = page.locator(b.sel).first();
      if (await el.isVisible({ timeout: 1000 })) {
        if (b.sel === 'textarea') {
          await el.fill('测试消息');
          console.log(`  ✅ ${b.desc} → 已输入`);
          pass++;
        } else {
          await el.click();
          console.log(`  ✅ ${b.desc}`);
          pass++;
        }
      } else { console.log(`  ⚠️ ${b.desc} → 不可见`); fail++; }
    } catch(e) { console.log(`  ❌ ${b.desc}`); fail++; }
  }

  // === Explore ===
  console.log('\n=== C1 经验广场 ===');
  await page.goto(`${BASE}/explore`);
  await page.waitForTimeout(1000);
  const c1btns = [
    { sel: 'input[type="text"]', desc: '搜索框' },
    { sel: 'button:has-text("破冰")', desc: '破冰标签' },
    { sel: 'select', desc: '排序下拉' },
  ];
  for (const b of c1btns) {
    if (await testButton(page, b.sel, b.desc)) pass++; else fail++;
  }

  // === Admin Experts ===
  console.log('\n=== D3 萃取师经验库 ===');
  await page.goto(`${BASE}/admin/experts`);
  await page.waitForTimeout(1000);
  const d3btns = [
    { sel: 'button:has-text("上传新材料")', desc: '上传新材料' },
    { sel: 'button:has-text("查看综合指令")', desc: '查看综合指令' },
    { sel: 'button:has-text("重新生成综合Skill")', desc: '重新生成综合Skill' },
  ];
  for (const b of d3btns) {
    if (await testButton(page, b.sel, b.desc)) pass++; else fail++;
  }

  // === Tools ===
  console.log('\n=== C5 工具箱 ===');
  await page.goto(`${BASE}/tools`);
  await page.waitForTimeout(1000);
  if (await page.locator('text=金句海报').isVisible()) { console.log('  ✅ 工具箱内容可见'); pass++; }
  else { console.log('  ❌ 工具箱无内容'); fail++; }

  // === Auth check ===
  console.log('\n=== E1 未登录保护 ===');
  const page2 = await context.newPage();
  await page2.goto(`${BASE}/interview/create`);
  await page2.waitForTimeout(2000);
  if (page2.url().includes('/login')) { console.log('  ✅ 未登录→跳转登录'); pass++; }
  else { console.log('  ❌ 未登录未保护'); fail++; }
  await page2.close();

  console.log(`\n=== 测试完成 ===`);
  console.log(`通过: ${pass}, 失败: ${fail}, 总计: ${pass+fail}`);
  await browser.close();
}

run().catch(console.error);
