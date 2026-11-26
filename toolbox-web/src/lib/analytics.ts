import { ref } from 'vue';
import type { ToolCategory } from '../types';
import { reportEvents } from '../services/stats-api';
import type { UsageEventPayload } from '../services/public-api';

/**
 * 匿名使用事件的最小化采集。
 * 只保存工具标识、事件类型、操作标识与时间，不保存文件名、题单内容或学生信息。
 * 本机历史仅作开发与本机查看，不代表全站数据。
 */

/** 事件类型。 */
export type UsageEventType = 'entry_click' | 'worksheet_success' | 'print_request';

/** 本机保留的历史事件，用于本机视图。 */
export interface LocalUsageEvent {
  eventId: string;
  eventType: UsageEventType;
  category: ToolCategory;
  toolId: string | null;
  operationId: string | null;
  occurredAt: string;
}

const LOCAL_KEY = 'yuyutian-toolbox:usage:v2';
const QUEUE_KEY = 'yuyutian-toolbox:events:v1';
const MAX_LOCAL = 2000;
const MAX_QUEUE = 500;
const BATCH_SIZE = 50;
const FLUSH_INTERVAL_MS = 15000;

const localEvents = ref<LocalUsageEvent[]>([]);
let queue: UsageEventPayload[] = [];
/** 已入队的 eventId，网络重发不会再产生一条新事件。 */
const queuedEventIds = new Set<string>();
/** 已入队的成功语义键，用于阻止同一题单重复计成功。 */
const queuedSuccessKeys = new Set<string>();
let flushTimer: ReturnType<typeof setInterval> | undefined;
let flushing = false;

/** 读取本机历史；非法数据一律丢弃。 */
function loadLocal(): void {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(LOCAL_KEY) ?? '[]');
    if (!Array.isArray(saved)) return;
    localEvents.value = saved.filter((item): item is LocalUsageEvent => Boolean(item)
      && typeof item.eventId === 'string'
      && ['entry_click', 'worksheet_success', 'print_request'].includes(item.eventType)
      && ['pdf', 'copybook', 'words'].includes(item.category)
      && typeof item.occurredAt === 'string').slice(-MAX_LOCAL);
  } catch { /* 本机存储不可用时仍可使用工具。 */ }
}

/** 读取尚未上报的队列。 */
function loadQueue(): void {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(QUEUE_KEY) ?? '[]');
    if (!Array.isArray(saved)) return;
    queue = (saved as UsageEventPayload[]).filter(item => Boolean(item) && typeof item.eventId === 'string' && typeof item.occurredAt === 'string').slice(-MAX_QUEUE);
    for (const item of queue) queuedEventIds.add(item.eventId);
  } catch { queue = []; }
}

/** 持久化本机历史。 */
function persistLocal(): void {
  try { localStorage.setItem(LOCAL_KEY, JSON.stringify(localEvents.value)); } catch { /* 保留当前会话统计。 */ }
}

/** 持久化待上报队列。 */
function persistQueue(): void {
  try { localStorage.setItem(QUEUE_KEY, JSON.stringify(queue)); } catch { /* 网络恢复后仍会尝试上报内存队列。 */ }
}

/** 生成一个新的操作身份。 */
export function newOperationId(): string {
  return globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : `op-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 入队一条最小化事件，重复 eventId 直接忽略。 */
function enqueue(payload: UsageEventPayload): void {
  if (queuedEventIds.has(payload.eventId)) return;
  queuedEventIds.add(payload.eventId);
  queue.push(payload);
  if (queue.length > MAX_QUEUE) {
    // 队列溢出时丢弃最旧事件，避免本机存储无限增长。
    const dropped = queue.splice(0, queue.length - MAX_QUEUE);
    for (const item of dropped) queuedEventIds.delete(item.eventId);
  }
  persistQueue();
  scheduleFlush();
}

/** 追加一条本机历史并持久化。 */
function appendLocal(payload: UsageEventPayload): void {
  localEvents.value = [...localEvents.value, {
    eventId: payload.eventId,
    eventType: payload.eventType,
    category: payload.category,
    toolId: payload.toolId,
    operationId: payload.operationId,
    occurredAt: payload.occurredAt
  }].slice(-MAX_LOCAL);
  persistLocal();
}

/** 记录一次工具入口点击；toolId 可为空表示只记录一级目录。 */
export function recordEntryClick(category: ToolCategory, toolId: string | null = null): void {
  const payload: UsageEventPayload = {
    eventId: newOperationId(),
    eventType: 'entry_click',
    category,
    toolId,
    occurredAt: new Date().toISOString(),
    operationId: null,
    schemaVersion: 1
  };
  appendLocal(payload);
  enqueue(payload);
}

/** 记录一次题单成功生成；同一 toolId 与 operationId 只计一次。 */
export function recordWorksheetSuccess(category: ToolCategory, toolId: string, operationId: string): void {
  const key = `${toolId}:${operationId}`;
  if (queuedSuccessKeys.has(key)) return;
  queuedSuccessKeys.add(key);
  const payload: UsageEventPayload = {
    eventId: newOperationId(),
    eventType: 'worksheet_success',
    category,
    toolId,
    occurredAt: new Date().toISOString(),
    operationId,
    schemaVersion: 1
  };
  appendLocal(payload);
  enqueue(payload);
}

/** 记录一次打印发起；每次真实调用产生新 eventId，网络重发才保持同一 eventId。 */
export function recordPrintRequest(category: ToolCategory, toolId: string, operationId: string): void {
  const payload: UsageEventPayload = {
    eventId: newOperationId(),
    eventType: 'print_request',
    category,
    toolId,
    occurredAt: new Date().toISOString(),
    operationId,
    schemaVersion: 1
  };
  appendLocal(payload);
  enqueue(payload);
}

let scheduled = false;

/** 在一次微任务后批量上报，避免连续操作产生多次请求。 */
function scheduleFlush(): void {
  if (scheduled) return;
  scheduled = true;
  void Promise.resolve().then(() => { scheduled = false; void flushEvents(); });
}

/** 上报队列中的事件；失败时保留队列等待下次重试。 */
export async function flushEvents(): Promise<void> {
  if (flushing || !queue.length) return;
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return;
  flushing = true;
  try {
    while (queue.length) {
      const batch = queue.slice(0, BATCH_SIZE);
      const results = await reportEvents(batch);
      // 只有服务端明确受理或判定重复的事件才从队列移除。
      const settled = new Set(results.filter(item => item.status !== 'rejected').map(item => item.eventId));
      const rejected = new Set(results.filter(item => item.status === 'rejected').map(item => item.eventId));
      if (!settled.size && !rejected.size) break;
      queue = queue.filter(item => {
        if (settled.has(item.eventId) || rejected.has(item.eventId)) {
          queuedEventIds.delete(item.eventId);
          return false;
        }
        return true;
      });
      persistQueue();
      if (rejected.size) break;
    }
  } catch {
    // 上报失败不影响工具使用，保留队列等待下次重试。
  } finally {
    flushing = false;
  }
}

/** 启动周期性上报与恢复上报。 */
export function startAnalytics(): void {
  loadLocal();
  loadQueue();
  if (flushTimer) return;
  flushTimer = setInterval(() => { void flushEvents(); }, FLUSH_INTERVAL_MS);
  if (typeof window !== 'undefined') {
    window.addEventListener('online', () => { void flushEvents(); });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void flushEvents(); });
  }
  void flushEvents();
}

/** 停止周期性上报。 */
export function stopAnalytics(): void {
  if (flushTimer) clearInterval(flushTimer);
  flushTimer = undefined;
}

/** 提供本机历史的响应式引用，仅用于本机视图。 */
export function useUsageEvents() {
  return localEvents;
}

/** 清空本机历史与待上报队列。 */
export function clearLocalUsage(): void {
  localEvents.value = [];
  queue = [];
  queuedEventIds.clear();
  queuedSuccessKeys.clear();
  persistLocal();
  persistQueue();
}

/** 统一使用北京时间生成自然日键。 */
export function dateKey(date: Date): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(date);
}
