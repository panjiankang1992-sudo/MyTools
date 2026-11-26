<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { NButton, NCard, NPopconfirm, NSwitch } from 'naive-ui';
import { newsApi } from '@/service/api/news';
import { useNewsText } from '@/locales/news';
import { useNewsDeliveryText } from '@/locales/news-delivery';
import NewsReader from './modules/news-reader.vue';
import NewsDeliveryPanel from './modules/news-delivery-panel.vue';
import { canCancelNewsRun, createNewsWorkspace, newsTime } from './model';

defineOptions({ name: 'NewsPage' });
const { text, status, error, locale } = useNewsText();
const { text: deliveryText } = useNewsDeliveryText();
const workspace = createNewsWorkspace(newsApi);
const { state } = workspace;
const route = useRoute();
const reader = ref<HTMLElement | null>(null);
const zone = computed(() => state.subscription?.subscription?.zoneId || 'Asia/Shanghai');
const topIssues = computed(() => ['capabilities', 'region', 'history', 'runs'].filter(key => state.issues[key]));
const subscriptionDirty = computed(() => {
  const saved = state.subscription?.subscription;
  return state.draft.enabled !== (saved?.enabled ?? false) || state.draft.zoneId !== (saved?.zoneId ?? 'Asia/Shanghai')
    || state.draft.cutoffTime !== (saved?.cutoffTime ?? '07:30');
});
let active = true;
let timer: ReturnType<typeof setInterval> | undefined;

function openDelivery() {
  const heading = document.getElementById('delivery-heading');
  heading?.scrollIntoView({ block: 'start' });
  heading?.focus({ preventScroll: true });
}

async function openEdition(id: string) {
  await workspace.selectEdition(id);
  reader.value?.focus({ preventScroll: true });
}

async function preview() {
  await workspace.preview();
  reader.value?.focus({ preventScroll: true });
}

onMounted(async () => {
  await workspace.initialize();
  if (!active) return;
  const requested = route.query.edition;
  const id = typeof requested === 'string' && /^[0-9a-f-]{36}$/i.test(requested) ? requested : state.history[0]?.id;
  if (id) await workspace.selectEdition(id);
  if (!active) return;
  timer = setInterval(() => {
    // 仅在可见页面且存在未完成任务时轮询，不覆盖表单与已选日报。
    if (!document.hidden && state.runs.some(canCancelNewsRun)) void workspace.refreshActivities();
  }, 20000);
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
  active = false;
  workspace.dispose();
});
</script>

<template>
  <main class="news-page" :aria-busy="state.loading" data-testid="news-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">{{ text.eyebrow }}</p>
        <h1>{{ text.title }}</h1>
        <p class="intro">{{ text.subtitle }}</p>
      </div>
      <div class="heading-actions">
        <NButton data-testid="open-delivery" @click="openDelivery">{{ deliveryText.title }}</NButton>
        <NButton :loading="state.refreshing" :disabled="state.loading" @click="workspace.refreshActivities">
          {{ text.refresh }}
        </NButton>
      </div>
    </header>
    <div v-if="topIssues.length" class="notice danger" role="alert">
      <span>{{ text.partialError }} {{ topIssues.map(key => `${key}: ${state.issues[key]}`).join(' · ') }}</span>
      <NButton size="small" :loading="state.loading" @click="workspace.refreshOverview">{{ text.retry }}</NButton>
    </div>
    <div class="workspace">
      <aside class="sidebar">
        <NCard :bordered="false" class="panel" content-style="padding: 22px">
          <h2>{{ text.subscription }}</h2>
          <p v-if="state.loading && !state.subscription" role="status">{{ text.loading }}</p>
          <div v-if="state.issues.subscription" class="notice danger" role="alert">
            <p>{{ error(state.issues.subscription) }}</p>
            <NButton size="small" @click="workspace.reloadSubscription">{{ text.retry }}</NButton>
          </div>
          <form v-if="state.subscription" class="subscription-form" @submit.prevent="workspace.save">
            <div class="switch-row">
              <label for="news-enabled">{{ text.enabled }}</label>
              <NSwitch
                id="news-enabled"
                v-model:value="state.draft.enabled"
                :disabled="state.saving"
                :aria-label="text.enabled"
                data-testid="news-enabled"
              />
            </div>
            <label for="news-zone">{{ text.zone }}</label>
            <input
              id="news-zone"
              v-model="state.draft.zoneId"
              list="news-zones"
              maxlength="64"
              :disabled="state.saving"
              required
              autocomplete="off"
            />
            <datalist id="news-zones">
              <option value="Asia/Shanghai" />
              <option value="Asia/Hong_Kong" />
              <option value="Asia/Tokyo" />
              <option value="Europe/London" />
              <option value="America/New_York" />
              <option value="UTC" />
            </datalist>
            <label for="news-time">{{ text.time }}</label>
            <input
              id="news-time"
              v-model="state.draft.cutoffTime"
              type="time"
              step="60"
              :disabled="state.saving"
              required
            />
            <p class="hint">{{ text.scheduleHint }}</p>
            <p v-if="!state.subscription.schedulingEnabled" class="notice">{{ text.scheduleOff }}</p>
            <p v-if="!state.subscription.deliveryEnabled" class="hint">{{ text.deliveryOff }}</p>
            <div v-if="state.issues.save" class="notice danger" role="alert">
              {{ error(state.issues.save) }}
              <code>{{ state.issues.save }}</code>
            </div>
            <NButton v-if="state.conflict" block :disabled="state.saving" @click="workspace.reloadSubscription">
              {{ text.reload }}
            </NButton>
            <NButton
              attr-type="submit"
              type="primary"
              block
              :loading="state.saving"
              :disabled="state.conflict"
              data-testid="save-subscription"
            >
              {{ text.save }}
            </NButton>
            <p v-if="state.saved && !subscriptionDirty" class="saved" role="status">{{ text.saved }}</p>
            <p class="hint next-due">
              {{ text.next }}
              <br />
              <strong>
                {{
                  state.subscription.subscription?.enabled
                    ? newsTime(state.subscription.subscription.nextDueAt, zone, locale)
                    : text.paused
                }}
              </strong>
              · {{ zone }}
            </p>
          </form>
        </NCard>
        <NCard :bordered="false" class="panel" content-style="padding: 22px">
          <h2>{{ text.currentRegion }}</h2>
          <p class="region-name">
            {{
              state.region?.details?.sourcePackLabel ||
                state.region?.details?.cityName ||
                state.region?.regionCode ||
                text.regionUnknown
            }}
          </p>
          <p v-if="state.region">{{ status(state.region.status) }}</p>
          <p class="hint">{{ text.regionHint }}</p>
          <p v-if="state.region?.nodeId" class="hint">{{ text.node }}: {{ state.region.nodeId }}</p>
        </NCard>
        <NCard :bordered="false" class="panel history-panel" content-style="padding: 22px">
          <h2>{{ text.history }}</h2>
          <p class="hint">{{ text.historyLimit }}</p>
          <p v-if="!state.history.length && !state.loading && !state.issues.history" class="hint">
            {{ text.emptyHistory }}
          </p>
          <ul class="history-list">
            <li v-for="item in state.history" :key="item.id">
              <button
                type="button"
                :aria-current="state.selectedId === item.id ? 'true' : undefined"
                @click="openEdition(item.id)"
              >
                <strong>{{ newsTime(item.cutoff, zone, locale) }}</strong>
                <span>{{ status(item.quality) }}</span>
              </button>
            </li>
          </ul>
        </NCard>
      </aside>
      <div class="reading-column">
        <NCard :bordered="false" class="panel preview-panel" content-style="padding: 20px 24px">
          <div class="preview-row">
            <div>
              <h2>{{ text.preview }}</h2>
              <p class="hint">{{ text.previewHint }}</p>
              <p v-if="state.capabilities" class="hint">
                {{ text.cutoff }}:
                {{ newsTime(state.previewAttempt?.cutoff || state.capabilities.latestCutoff, zone, locale) }} ·
                {{ zone }}
              </p>
            </div>
            <NButton
              type="primary"
              :loading="state.previewing"
              :disabled="!state.capabilities || state.loading"
              data-testid="generate-preview"
              @click="preview"
            >
              {{ state.previewAttempt ? text.previewRetry : text.preview }}
            </NButton>
          </div>
          <p v-if="state.issues.preview" class="notice danger" role="alert">
            {{ text.previewUnknown }}
            <code>{{ state.issues.preview }}</code>
          </p>
        </NCard>
        <NCard :bordered="false" class="panel reader-panel" content-style="padding: clamp(18px, 3vw, 32px)">
          <div ref="reader" tabindex="-1" class="reader-focus" :aria-label="text.edition" :aria-busy="state.reading">
            <p v-if="state.reading" class="empty" role="status">{{ text.loading }}</p>
            <div v-else-if="state.issues.edition" class="notice danger" role="alert">
              <p>{{ error(state.issues.edition) }}</p>
              <NButton @click="openEdition(state.selectedId)">{{ text.retry }}</NButton>
            </div>
            <NewsReader v-else-if="state.edition" :edition="state.edition" />
            <p v-else class="empty">{{ text.readerEmpty }}</p>
          </div>
        </NCard>
        <NCard :bordered="false" class="panel" content-style="padding: 24px">
          <h2>{{ text.runs }}</h2>
          <p class="hint">{{ text.runHint }}</p>
          <p v-if="state.issues.cancel" class="notice danger" role="alert">
            {{ error(state.issues.cancel) }}
            <code>{{ state.issues.cancel }}</code>
          </p>
          <p v-if="!state.runs.length && !state.loading && !state.issues.runs" class="hint">{{ text.emptyRuns }}</p>
          <ul class="run-list">
            <li v-for="run in state.runs" :key="run.id">
              <div>
                <strong>{{ run.businessDate }} · {{ status(run.status) }}</strong>
                <p class="hint">{{ newsTime(run.cutoff, run.zoneId, locale) }} · {{ run.zoneId }}</p>
                <p v-if="run.taskStatus" class="hint">{{ text.taskStatus }}: {{ run.taskStatus }}</p>
                <p v-if="run.errorCode" class="error-code">{{ text.errorCode }}: {{ run.errorCode }}</p>
                <p v-if="run.skippedDays" class="hint">{{ text.skipped }}: {{ run.skippedDays }}</p>
              </div>
              <NButton v-if="run.editionId" size="small" @click="openEdition(run.editionId)">{{ text.read }}</NButton>
              <NPopconfirm
                v-else-if="canCancelNewsRun(run)"
                :positive-text="text.confirm"
                :negative-text="text.keep"
                @positive-click="workspace.cancel(run)"
              >
                <template #trigger>
                  <NButton size="small" :loading="state.cancelId === run.id" :disabled="Boolean(state.cancelId)">
                    {{ text.cancel }}
                  </NButton>
                </template>
                {{ text.confirmCancel }}
              </NPopconfirm>
            </li>
          </ul>
        </NCard>
      </div>
    </div>
    <NewsDeliveryPanel :zone="zone" @read="openEdition" />
  </main>
</template>

<style scoped>
.news-page {
  width: 100%;
  max-width: 1440px;
  margin: 0 auto;
  padding: 12px 8px 32px;
}
.page-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin: 6px 0 26px;
}
.heading-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.eyebrow {
  font-size: 11px;
  font-weight: 650;
  color: #43856b;
  letter-spacing: 0.14em;
  margin-bottom: 8px;
}
h1 {
  font-size: 30px;
  font-weight: 650;
  letter-spacing: -0.025em;
}
.intro {
  font-size: 13px;
  opacity: 0.7;
  margin-top: 10px;
  line-height: 1.8;
}
.workspace {
  display: grid;
  grid-template-columns: minmax(245px, 300px) minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}
.sidebar,
.reading-column {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}
.panel {
  border-radius: 12px;
  box-shadow: 0 2px 16px #172f2005;
}
h2 {
  font-size: 16px;
  font-weight: 650;
  margin-bottom: 10px;
}
.hint {
  font-size: 12px;
  opacity: 0.68;
  line-height: 1.8;
  margin: 8px 0;
  overflow-wrap: anywhere;
}
.subscription-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.subscription-form label {
  font-size: 13px;
}
.switch-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 8px 0;
}
input {
  width: 100%;
  min-width: 0;
  border: 1px solid #9daaa84d;
  background: transparent;
  border-radius: 6px;
  padding: 8px 10px;
  color: inherit;
  font: inherit;
  font-size: 14px;
}
input:focus-visible,
button:focus-visible {
  outline: 2px solid #278263;
  outline-offset: 2px;
}
input:disabled {
  opacity: 0.6;
}
.next-due {
  border-top: 1px solid #9daaa830;
  padding-top: 14px;
}
.notice {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  background: #b5923114;
  padding: 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.8;
  margin-bottom: 12px;
}
.danger {
  background: #c9543f10;
  color: #bd554b;
}
.saved {
  font-size: 12px;
  color: #278263;
}
.region-name {
  font-size: 16px;
  margin: 14px 0 6px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.history-list {
  list-style: none;
  margin: 12px -8px 0;
  padding: 0;
  max-height: 400px;
  overflow-y: auto;
}
.history-list button {
  text-align: left;
  width: 100%;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-radius: 6px;
  cursor: pointer;
}
.history-list button:hover,
.history-list button[aria-current] {
  background: #63877414;
}
.history-list strong {
  font-size: 13px;
  font-weight: 500;
}
.history-list span {
  font-size: 11px;
  opacity: 0.6;
}
.preview-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
.empty {
  text-align: center;
  padding: 60px 12px;
  opacity: 0.6;
}
.reader-focus:focus-visible {
  outline: 2px solid #278263;
  outline-offset: 8px;
}
.run-list {
  list-style: none;
  padding: 0;
  margin: 12px 0 0;
}
.run-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 0;
  border-top: 1px solid #9daaa830;
}
.run-list strong {
  font-size: 13px;
  font-weight: 550;
}
.error-code {
  font-size: 12px;
  color: #bd554b;
}
@media (max-width: 900px) {
  .workspace {
    grid-template-columns: 1fr;
  }
  .sidebar {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
  .history-panel {
    grid-column: 1 / -1;
  }
  .history-list {
    max-height: 200px;
  }
}
@media (max-width: 600px) {
  .news-page {
    padding: 6px 0 20px;
  }
  .sidebar {
    display: flex;
  }
  .page-heading {
    align-items: start;
  }
  h1 {
    font-size: 26px;
  }
  .preview-row {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }
  .run-list li {
    flex-wrap: wrap;
  }
}
</style>
