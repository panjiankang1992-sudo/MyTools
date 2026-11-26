#!/usr/bin/env node
/**
 * 本地验收用的管理员草稿播种脚本（**只允许回环目标**）。
 *
 * 为什么需要它：四题型预览要求"每种题型至少 3 页"。隔离库里既有草稿只有 2 个词条，
 * 而且单字条目在 A4 上密度很高（约 60 条/页），凑不到三页。
 * 这里通过**真实的**管理 HTTP 链路（CSRF → 登录 → 建草稿 → CAS 保存）造出
 * 排得满三页以上的语文／英语草稿，供浏览器验收生成 PDF 时使用。
 *
 * 规模上限来自前端处理层：一次选入最多 200 条（`PROCESSING_LIMITS.maxSelected`），
 * 因此这里播种 **198** 条——验收里的"改内容不保存就预览"还要再加 2 条，正好顶到 200。
 *
 * 它不写任何生产：目标经 `requireLoopbackTarget` 校验，非回环直接退出码 2。
 *
 * 用法：
 *   E2E_TARGET=http://127.0.0.1:4173 node tests/e2e/seed-admin-textbooks.mjs
 */

import { requireLoopbackTarget } from './target-guard.mjs';

const base = requireLoopbackTarget(process.env.E2E_TARGET, 'E2E_TARGET');
const account = process.env.E2E_ADMIN_ACCOUNT ?? 'local-admin';
const password = process.env.E2E_ADMIN_PASSWORD ?? 'local-admin';
/** 每个教材播种的条目数；198 + 2（验收时的追加）= 200 上限。 */
const ENTRY_TARGET = 198;

/** 语文词根：两个字的词与逐字拼音（拼接后是四字词、四音节，页宽利用率更接近真实词表）。 */
const CHINESE_ROOTS = [
  ['天地', 'tiān dì'], ['人和', 'rén hé'], ['山水', 'shān shuǐ'], ['日月', 'rì yuè'],
  ['花草', 'huā cǎo'], ['树木', 'shù mù'], ['风雨', 'fēng yǔ'], ['云海', 'yún hǎi'],
  ['星光', 'xīng guāng'], ['春夏', 'chūn xià'], ['秋冬', 'qiū dōng'], ['东南', 'dōng nán'],
  ['西北', 'xī běi'], ['上下', 'shàng xià'], ['左右', 'zuǒ yòu'], ['前后', 'qián hòu'],
  ['大小', 'dà xiǎo'], ['多少', 'duō shǎo'], ['高低', 'gāo dī'], ['长短', 'cháng duǎn'],
  ['远近', 'yuǎn jìn'], ['红黄', 'hóng huáng'], ['蓝绿', 'lán lǜ'], ['白黑', 'bái hēi'],
  ['学习', 'xué xí'], ['生活', 'shēng huó'], ['老师', 'lǎo shī'], ['朋友', 'péng yǒu'],
  ['家国', 'jiā guó'], ['江河', 'jiāng hé']
];

/** 英语词表：真实单词与中文释义。 */
const ENGLISH_WORDS = [
  ['apple', '苹果'], ['banana', '香蕉'], ['orange', '橙子'], ['grape', '葡萄'],
  ['water', '水'], ['milk', '牛奶'], ['bread', '面包'], ['rice', '米饭'],
  ['book', '书'], ['pen', '钢笔'], ['pencil', '铅笔'], ['desk', '书桌'],
  ['chair', '椅子'], ['window', '窗户'], ['door', '门'], ['school', '学校'],
  ['teacher', '老师'], ['student', '学生'], ['friend', '朋友'], ['family', '家庭'],
  ['mother', '母亲'], ['father', '父亲'], ['sister', '姐妹'], ['brother', '兄弟'],
  ['morning', '早晨'], ['evening', '傍晚'], ['night', '夜晚'], ['today', '今天'],
  ['tomorrow', '明天'], ['yesterday', '昨天'], ['spring', '春天'], ['summer', '夏天'],
  ['autumn', '秋天'], ['winter', '冬天'], ['river', '河流'], ['mountain', '山'],
  ['flower', '花'], ['tree', '树'], ['bird', '鸟'], ['fish', '鱼'],
  ['happy', '快乐的'], ['sad', '悲伤的'], ['big', '大的'], ['small', '小的'],
  ['red', '红色的'], ['blue', '蓝色的'], ['green', '绿色的'], ['white', '白色的'],
  ['black', '黑色的'], ['run', '跑'], ['jump', '跳'], ['sing', '唱歌'],
  ['dance', '跳舞'], ['read', '阅读'], ['write', '书写'], ['listen', '听'],
  ['speak', '说'], ['open', '打开'], ['close', '关闭'], ['clean', '打扫'],
  ['garden', '花园'], ['kitchen', '厨房'], ['bedroom', '卧室'], ['bathroom', '浴室'],
  ['library', '图书馆'], ['hospital', '医院'], ['station', '车站'], ['market', '市场'],
  ['doctor', '医生'], ['nurse', '护士'], ['farmer', '农民'], ['driver', '司机'],
  ['police', '警察'], ['worker', '工人'], ['pupil', '小学生'], ['classmate', '同学'],
  ['holiday', '假日'], ['birthday', '生日'], ['weather', '天气'], ['windy', '有风的'],
  ['sunny', '晴朗的'], ['cloudy', '多云的'], ['rainy', '下雨的'], ['snowy', '下雪的'],
  ['animal', '动物'], ['panda', '熊猫'], ['rabbit', '兔子'], ['monkey', '猴子'],
  ['elephant', '大象'], ['tiger', '老虎'], ['horse', '马'], ['sheep', '绵羊'],
  ['pencil-box', '铅笔盒'], ['notebook', '笔记本'], ['blackboard', '黑板'], ['classroom', '教室'],
  ['playground', '操场'], ['basketball', '篮球'], ['football', '足球'], ['swimming', '游泳'],
  ['music', '音乐'], ['picture', '图画'], ['story', '故事'], ['question', '问题'],
  ['answer', '答案'], ['lesson', '课程'], ['homework', '作业'], ['exam', '考试'],
  ['breakfast', '早餐'], ['lunch', '午餐'], ['dinner', '晚餐'], ['vegetable', '蔬菜'],
  ['fruit', '水果'], ['chicken', '鸡肉'], ['noodle', '面条'], ['dumpling', '饺子'],
  ['bicycle', '自行车'], ['bus', '公共汽车'], ['train', '火车'], ['plane', '飞机'],
  ['ship', '轮船'], ['road', '道路'], ['bridge', '桥'], ['village', '村庄'],
  ['city', '城市'], ['country', '国家'], ['world', '世界'], ['people', '人们'],
  ['language', '语言'], ['English', '英语'], ['Chinese', '中文'], ['maths', '数学'],
  ['science', '科学'], ['history', '历史'], ['computer', '电脑'], ['phone', '电话'],
  ['letter', '信件'], ['message', '消息'], ['newspaper', '报纸'], ['magazine', '杂志'],
  ['camera', '照相机'], ['clock', '时钟'], ['watch', '手表'], ['mirror', '镜子'],
  ['bottle', '瓶子'], ['cup', '杯子'], ['plate', '盘子'], ['spoon', '勺子'],
  ['brown', '棕色的'], ['pink', '粉色的'], ['purple', '紫色的'], ['grey', '灰色的'],
  ['yellow', '黄色的'], ['one', '一'], ['two', '二'], ['three', '三'],
  ['four', '四'], ['five', '五'], ['six', '六'], ['seven', '七'],
  ['eight', '八'], ['nine', '九'], ['ten', '十'], ['Monday', '星期一'],
  ['Tuesday', '星期二'], ['Wednesday', '星期三'], ['Thursday', '星期四'], ['Friday', '星期五'],
  ['Saturday', '星期六'], ['Sunday', '星期日'], ['January', '一月'], ['February', '二月'],
  ['March', '三月'], ['April', '四月'], ['May', '五月'], ['June', '六月'],
  ['July', '七月'], ['August', '八月'], ['September', '九月'], ['October', '十月'],
  ['November', '十一月'], ['December', '十二月'], ['swim', '游泳'], ['draw', '画画'],
  ['climb', '攀爬'], ['walk', '走路'], ['fly', '飞'], ['cook', '做饭'],
  ['wash', '洗'], ['help', '帮助'], ['study', '学习'], ['play', '玩耍'],
  ['warm', '温暖的'], ['cool', '凉爽的'], ['hot', '炎热的'], ['cold', '寒冷的'],
  ['young', '年轻的'], ['old', '年老的']
];

/** 每次运行的 Cookie 罐（只在内存里）。 */
const cookies = new Map();

/** 记录 Cookie。 */
function absorbCookies(response) {
  const list = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie() : [];
  for (const raw of list) {
    const [pair] = raw.split(';');
    const index = pair.indexOf('=');
    if (index <= 0) continue;
    const name = pair.slice(0, index).trim();
    const value = pair.slice(index + 1).trim();
    if (!value) cookies.delete(name);
    else cookies.set(name, value);
  }
}

/** 拼 Cookie 头。 */
function cookieHeader() {
  return [...cookies.entries()].map(([name, value]) => `${name}=${value}`).join('; ');
}

/**
 * 发起一次管理接口请求。
 *
 * @param path    以 `/admin/v1` 开头的路径
 * @param options 方法、请求体与附加头
 * @returns 状态码与已解析的响应体
 */
async function adminRequest(path, options = {}) {
  const headers = { Accept: 'application/json', ...(options.headers ?? {}) };
  if (cookies.size) headers.Cookie = cookieHeader();
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`${base}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    redirect: 'manual'
  });
  absorbCookies(response);
  const text = await response.text();
  let payload = null;
  try { payload = JSON.parse(text); } catch { /* 非 JSON */ }
  return { status: response.status, headers: response.headers, payload, text };
}

/** 语文条目：四字词 + 四音节拼音。 */
function chineseEntries(editionId) {
  const rows = [];
  for (const [first, firstPinyin] of CHINESE_ROOTS) {
    for (const [second, secondPinyin] of CHINESE_ROOTS) {
      if (first === second) continue;
      rows.push([`${first}${second}`, `${firstPinyin} ${secondPinyin}`]);
      if (rows.length >= ENTRY_TARGET) break;
    }
    if (rows.length >= ENTRY_TARGET) break;
  }
  return rows.map(([text, pinyin]) => ({ text, pinyin, meaning: '' }));
}

/** 英语条目：单词／短语 + 中文释义。 */
function englishEntries() {
  if (ENGLISH_WORDS.length < ENTRY_TARGET) {
    throw new Error(`英语词表只有 ${ENGLISH_WORDS.length} 条，少于目标 ${ENTRY_TARGET} 条`);
  }
  return ENGLISH_WORDS.slice(0, ENTRY_TARGET).map(([text, meaning]) => ({ text, pinyin: '', meaning }));
}

/** 把词表转成草稿条目（按 3 个单元平均分配）。 */
function buildEntries(subject, editionId) {
  const source = subject === 'english' ? englishEntries() : chineseEntries(editionId);
  const perUnit = Math.ceil(source.length / 3);
  return source.map((row, index) => ({
    rowId: `row-${editionId}-${index + 1}`,
    entryId: null,
    unitId: null,
    clientUnitId: `cu${Math.min(2, Math.floor(index / perUnit)) + 1}`,
    lessonId: null,
    clientLessonId: null,
    text: row.text,
    pinyin: row.pinyin,
    meaning: row.meaning,
    listTypes: subject === 'english' ? ['vocabulary'] : ['writing'],
    confirmed: true
  }));
}

/** 生成一份完整草稿。 */
function buildDraft(subject, editionId, series) {
  return {
    editionId,
    subject,
    grade: subject === 'english' ? 3 : 1,
    semester: 'first',
    publisher: '人民教育出版社',
    series,
    editionLabel: '2026 年本地验收版',
    sourceNote: '本地隔离库验收用合成样例数据，不代表真实教材内容。',
    coverageDeclaration: 'complete',
    units: [
      { unitId: null, clientUnitId: 'cu1', title: '第一单元', order: 1 },
      { unitId: null, clientUnitId: 'cu2', title: '第二单元', order: 2 },
      { unitId: null, clientUnitId: 'cu3', title: '第三单元', order: 3 }
    ],
    lessons: [
      { lessonId: null, clientLessonId: 'cl1', clientUnitId: 'cu1', title: '第一课', order: 1 },
      { lessonId: null, clientLessonId: 'cl2', clientUnitId: 'cu1', title: '第二课', order: 2 },
      { lessonId: null, clientLessonId: 'cl3', clientUnitId: 'cu2', title: '第三课', order: 3 },
      { lessonId: null, clientLessonId: 'cl4', clientUnitId: 'cu2', title: '第四课', order: 4 },
      { lessonId: null, clientLessonId: 'cl5', clientUnitId: 'cu3', title: '第五课', order: 5 },
      { lessonId: null, clientLessonId: 'cl6', clientUnitId: 'cu3', title: '第六课', order: 6 }
    ],
    entries: buildEntries(subject, editionId),
    idMapping: {}
  };
}

/** 登录本地假身份管理员。 */
async function login() {
  const csrf = await adminRequest('/admin/v1/auth/csrf');
  if (csrf.status !== 200) throw new Error(`领取 CSRF 失败：HTTP ${csrf.status} ${csrf.text.slice(0, 200)}`);
  const nonce = csrf.payload?.data?.csrfToken;
  const logged = await adminRequest('/admin/v1/auth/login', {
    method: 'POST',
    headers: { 'X-Toolbox-Admin-Csrf': nonce },
    body: { account, password }
  });
  if (logged.status !== 200) throw new Error(`登录失败：HTTP ${logged.status} ${logged.text.slice(0, 200)}`);
  return logged.payload.data.csrfToken;
}

/**
 * 确保某本教材的草稿存在且内容等于本次播种内容。
 *
 * @param csrf     会话 CSRF 令牌
 * @param subject  学科
 * @param editionId 教材标识
 * @param series   教材名称
 * @returns 观测结果（新建还是覆盖、条目数与修订号）
 */
async function seedEdition(csrf, subject, editionId, series) {
  const draft = buildDraft(subject, editionId, series);
  const existing = await adminRequest(`/admin/v1/textbooks/${editionId}/draft`);
  let created = false;
  if (existing.status === 404) {
    const response = await adminRequest('/admin/v1/textbooks', {
      method: 'POST',
      headers: { 'X-Toolbox-Admin-Csrf': csrf, 'Idempotency-Key': `seed-create-${editionId}-${Date.now()}` },
      body: draft
    });
    if (response.status !== 201) {
      throw new Error(`创建 ${editionId} 失败：HTTP ${response.status} ${response.text.slice(0, 300)}`);
    }
    created = true;
  } else if (existing.status !== 200) {
    throw new Error(`读取 ${editionId} 草稿失败：HTTP ${existing.status} ${existing.text.slice(0, 300)}`);
  }

  // 再读一次拿到最新修订号：新建后 revision 已经是 2，覆盖前必须先取到它。
  const current = await adminRequest(`/admin/v1/textbooks/${editionId}/draft`);
  const revision = current.payload?.data?.revision;
  const currentEntries = current.payload?.data?.draft?.entries ?? [];
  const sameContent = !created && currentEntries.length === draft.entries.length
    && currentEntries[0]?.text === draft.entries[0]?.text
    && currentEntries[ENTRY_TARGET - 1]?.text === draft.entries[ENTRY_TARGET - 1]?.text;
  if (sameContent) {
    return { editionId, subject, created, entryCount: currentEntries.length, revision, saved: false };
  }
  if (!Number.isInteger(revision)) {
    throw new Error(`读取 ${editionId} 修订号失败：${current.text.slice(0, 300)}`);
  }
  const saved = await adminRequest(`/admin/v1/textbooks/${editionId}/draft`, {
    method: 'PUT',
    headers: {
      'X-Toolbox-Admin-Csrf': csrf,
      'If-Match': `"draft-${revision}"`,
      'Idempotency-Key': `seed-save-${editionId}-${Date.now()}`
    },
    body: draft
  });
  if (saved.status !== 200) {
    throw new Error(`保存 ${editionId} 失败：HTTP ${saved.status} ${saved.text.slice(0, 300)}`);
  }
  const verify = await adminRequest(`/admin/v1/textbooks/${editionId}/draft`);
  return {
    editionId,
    subject,
    created,
    entryCount: verify.payload?.data?.draft?.entries?.length ?? 0,
    revision: verify.payload?.data?.revision,
    saved: true
  };
}

const csrf = await login();
const results = [];
for (const [subject, editionId, series] of [
  ['chinese', 'accept-cn-2026a', '语文验收样册'],
  ['english', 'accept-en-2026a', '英语验收样册']
]) {
  results.push(await seedEdition(csrf, subject, editionId, series));
}

console.log(JSON.stringify({ base, account, target: ENTRY_TARGET, editions: results }, null, 2));
