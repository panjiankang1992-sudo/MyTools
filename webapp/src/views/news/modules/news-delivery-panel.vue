<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { NButton, NCard, NPopconfirm, NSwitch } from 'naive-ui';
import { newsDeliveryApi } from '@/service/api/news';
import { useNewsDeliveryText } from '@/locales/news-delivery';
import { canRetryDelivery, canStopDelivery, createNewsDeliveryWorkspace, needsDeliveryRefresh } from '../delivery-model';
import { newsTime } from '../model';

defineOptions({ name: 'NewsDeliveryPanel' });
const props = defineProps<{ zone: string }>();
const emit = defineEmits<{ read: [id: string] }>();
const { text, status, error, locale } = useNewsDeliveryText();
const workspace = createNewsDeliveryWorkspace(newsDeliveryApi);
const { state } = workspace;
const selected = ref('');
const locked = computed(() => state.saving || state.loadingTargets);
const dirty = computed(() => JSON.stringify(state.draft) !== JSON.stringify(state.targets?.targets || []));
const candidates = computed(() => state.destinations.filter(item => item.enabled
  && state.capabilities?.supportedChannels.includes(item.channel)
  && !state.draft.some(target => target.destinationId === item.id)));
const overviewIssues = computed(() => ['capabilities', 'destinations', 'history'].filter(key => state.issues[key]));
let timer: ReturnType<typeof setInterval> | undefined;
let active = true;

function destinationLabel(id: string): string {
  return state.destinations.find(item => item.id === id)?.label || `${text.value.missingDestination} · ${id.slice(0, 8)}`;
}

function available(id: string): boolean {
  return Boolean(state.capabilities?.enabled && state.destinations.some(item => item.id === id && item.enabled
    && state.capabilities?.supportedChannels.includes(item.channel)));
}

function add() {
  workspace.addTarget(selected.value);
  selected.value = '';
}

onMounted(async () => {
  await workspace.initialize();
  if (!active) return;
  timer = setInterval(() => {
    // 可见页面才轮询；后台刷新不会重置接收草稿。
    if (!document.hidden && state.history.some(needsDeliveryRefresh)) void workspace.refreshHistory();
  }, 20000);
});
onUnmounted(() => {
  active = false;
  if (timer) clearInterval(timer);
  workspace.dispose();
});
</script>

<template>
  <section class="delivery-section" aria-labelledby="delivery-heading" data-testid="news-delivery">
    <header class="delivery-heading">
      <div>
        <h2 id="delivery-heading" tabindex="-1">{{ text.title }}</h2>
        <p class="hint">{{ text.intro }}</p>
      </div>
      <NButton :loading="state.refreshing" :disabled="state.loading" data-testid="refresh-delivery" @click="workspace.refreshOverview">
        {{ text.refresh }}
      </NButton>
    </header>
    <div v-for="key in overviewIssues" :key="key" role="alert" class="notice danger">
      {{ error(state.issues[key]) }} <code>{{ key }}: {{ state.issues[key] }}</code>
    </div>
    <p v-if="state.capabilities && !state.capabilities.enabled" class="notice">{{ text.disabled }}</p>
    <div class="delivery-grid">
      <NCard :bordered="false" class="delivery-card" content-style="padding: 22px">
        <h3>{{ text.settings }}</h3>
        <p class="hint">{{ text.binding }}</p>
        <p class="hint">{{ text.qq }}</p>
        <p v-if="state.loadingTargets" role="status">{{ text.loading }}</p>
        <div v-if="state.issues.targets" class="notice danger" role="alert">
          {{ error(state.issues.targets) }} <code>{{ state.issues.targets }}</code>
          <NButton :disabled="locked" @click="workspace.reloadTargets">{{ text.reload }}</NButton>
        </div>
        <form v-if="state.targets" @submit.prevent="workspace.save">
          <ul class="target-list">
            <li v-for="target in state.draft" :key="target.destinationId" :data-target="target.destinationId">
              <strong>{{ destinationLabel(target.destinationId) }}</strong>
              <span class="channel">{{ target.channel }}</span>
              <div class="target-controls">
                <NSwitch
                  v-model:value="target.enabled" :disabled="locked || (!target.enabled && !available(target.destinationId))"
                  :aria-label="`${text.enabled}: ${destinationLabel(target.destinationId)}`"
                />
                <span>{{ text.enabled }}</span>
              </div>
              <p v-if="!available(target.destinationId) && state.capabilities?.enabled" class="hint">{{ text.destinationDisabled }}</p>
              <label :for="`delivery-mode-${target.destinationId}`">{{ text.mode }}</label>
              <select :id="`delivery-mode-${target.destinationId}`" v-model="target.mode" :disabled="locked">
                <option value="FULL">{{ text.full }}</option>
                <option value="BRIEF">{{ text.brief }}</option>
              </select>
              <NButton
                size="small" :disabled="locked" :data-testid="`remove-target-${target.destinationId}`"
                @click="state.draft = state.draft.filter(item => item.destinationId !== target.destinationId)"
              >
                {{ text.remove }}
              </NButton>
            </li>
          </ul>
          <p v-if="!state.draft.length" class="hint">{{ text.emptyTargets }}</p>
          <div v-if="state.capabilities?.enabled && candidates.length" class="add-target">
            <label for="delivery-destination">{{ text.choose }}</label>
            <select id="delivery-destination" v-model="selected" :disabled="locked">
              <option value="" disabled>{{ text.choose }}</option>
              <option v-for="item in candidates" :key="item.id" :value="item.id">{{ item.label }} · {{ item.channel }}</option>
            </select>
            <NButton
              :disabled="locked || !selected || state.draft.length >= Math.min(5, state.capabilities.targetLimit)"
              data-testid="add-delivery-target" @click="add"
            >
              {{ text.add }}
            </NButton>
          </div>
          <p v-else-if="!state.destinations.length && !state.loading && !state.issues.destinations && state.capabilities?.enabled" class="hint">
            {{ text.emptyDestinations }}
          </p>
          <p class="hint">{{ text.modeHint }}</p>
          <p class="hint">{{ text.effective }}</p>
          <div v-if="state.issues.save" class="notice danger" role="alert" data-testid="delivery-save-error">
            {{ error(state.issues.save) }} <code>{{ state.issues.save }}</code>
          </div>
          <div class="form-actions">
            <NButton
              type="primary" attr-type="submit" :loading="state.saving"
              :disabled="state.conflict || locked || !dirty" data-testid="save-delivery-targets"
            >
              {{ text.save }}
            </NButton>
            <NPopconfirm :positive-text="text.confirm" :negative-text="text.keep" @positive-click="workspace.reloadTargets">
              <template #trigger>
                <NButton :disabled="locked" data-testid="reload-delivery-targets">{{ text.reload }}</NButton>
              </template>
              {{ text.discard }}
            </NPopconfirm>
          </div>
          <p v-if="state.saved && !dirty" class="saved" role="status" data-testid="delivery-saved">{{ text.saved }}</p>
        </form>
      </NCard>
      <NCard :bordered="false" class="delivery-card" content-style="padding: 22px">
        <h3>{{ text.history }}</h3>
        <p class="hint">{{ text.historyHint }}</p>
        <p v-if="state.loading" role="status">{{ text.loading }}</p>
        <div v-if="state.issues.action" class="notice danger" role="alert" data-testid="delivery-action-error">
          {{ error(state.issues.action) }} <code>{{ state.issues.action }}</code>
        </div>
        <p v-if="!state.history.length && !state.loading && !state.issues.history" class="hint">{{ text.emptyHistory }}</p>
        <ul class="delivery-history">
          <li v-for="(batch, index) in state.history" :key="batch.id" :data-delivery="batch.id">
            <div class="batch-heading">
              <strong>{{ destinationLabel(batch.destinationId) }}</strong>
              <span class="channel">{{ batch.channel }} · {{ batch.mode === 'FULL' ? text.full : text.brief }}</span>
            </div>
            <p class="batch-status" :data-status="batch.status">{{ status(batch.status) }}</p>
            <p class="hint">{{ newsTime(batch.createdAt, props.zone, locale) }} · {{ props.zone }}</p>
            <p class="hint">{{ text.expires }}: {{ newsTime(batch.expiresAt, props.zone, locale) }}</p>
            <p v-if="batch.cancelRequested" class="notice">{{ text.stopPending }}</p>
            <p v-if="batch.errorCode" class="error-code">{{ batch.errorCode }}</p>
            <div class="batch-actions">
              <NButton v-if="batch.editionId" size="small" @click="emit('read', batch.editionId)">{{ text.read }}</NButton>
              <NPopconfirm
                v-if="canStopDelivery(batch)" :positive-text="text.confirm" :negative-text="text.keep"
                @positive-click="workspace.cancel(batch)"
              >
                <template #trigger>
                  <NButton
                    size="small" :disabled="Boolean(state.busy)" :loading="state.busy === `cancel:${batch.id}`"
                    :data-testid="`stop-delivery-${batch.id}`"
                  >
                    {{ text.stop }}
                  </NButton>
                </template>
                {{ text.stopConfirm }}
              </NPopconfirm>
            </div>
            <details v-if="batch.parts.length" :open="index === 0">
              <summary>{{ text.part }} · {{ batch.parts.length }}</summary>
              <ol class="part-list">
                <li v-for="part in batch.parts" :key="part.id" :data-part="part.id">
                  <div><strong>{{ part.index }}/{{ part.count }}</strong> · {{ status(part.status) }}</div>
                  <p class="hint">{{ text.generation }}: {{ part.generation }}</p>
                  <p v-if="part.errorCode" class="error-code">{{ part.errorCode }}</p>
                  <p v-if="part.retryPending" class="notice">{{ text.retryPending }}</p>
                  <template v-if="state.retryAttempts[part.id]">
                    <p class="notice">{{ text.retryUnknownRequest }}</p>
                    <NButton
                      size="small" :disabled="Boolean(state.busy) || !state.capabilities?.enabled || !canRetryDelivery(batch, part)"
                      :data-testid="`resume-retry-${part.id}`" @click="workspace.retry(batch, part)"
                    >
                      {{ text.resume }}
                    </NButton>
                  </template>
                  <NPopconfirm
                    v-else-if="state.capabilities?.enabled && canRetryDelivery(batch, part)"
                    :positive-text="part.status === 'UNKNOWN' ? text.acceptRisk : text.confirm" :negative-text="text.keep"
                    @positive-click="workspace.retry(batch, part, part.status === 'UNKNOWN')"
                  >
                    <template #trigger>
                      <NButton size="small" :disabled="Boolean(state.busy)" :data-testid="`retry-delivery-${part.id}`">
                        {{ part.status === 'UNKNOWN' ? text.retryUnknown : text.retry }}
                      </NButton>
                    </template>
                    {{ part.status === 'UNKNOWN' ? text.duplicateRisk : text.retryConfirm }}
                  </NPopconfirm>
                  <NPopconfirm
                    v-if="part.status === 'REJECTED' && !batch.cancelRequested && Date.parse(batch.expiresAt) > Date.now()"
                    :positive-text="text.confirm" :negative-text="text.keep" @positive-click="workspace.redrive(batch, part)"
                  >
                    <template #trigger>
                      <NButton size="small" :disabled="Boolean(state.busy)" :data-testid="`redrive-delivery-${part.id}`">{{ text.redrive }}</NButton>
                    </template>
                    {{ text.redriveConfirm }}
                  </NPopconfirm>
                </li>
              </ol>
            </details>
          </li>
        </ul>
      </NCard>
    </div>
  </section>
</template>

<style scoped>
.delivery-section { margin-top: 28px; min-width: 0; }
.delivery-heading, .batch-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.delivery-heading { margin-bottom: 18px; }
h2 { font-size: 20px; font-weight: 650; }
h3 { font-size: 16px; font-weight: 650; margin-bottom: 12px; }
.delivery-grid { display: grid; grid-template-columns: minmax(280px, 0.8fr) minmax(0, 1.2fr); gap: 22px; align-items: start; }
.delivery-card { border-radius: 12px; min-width: 0; }
.hint { font-size: 12px; opacity: 0.72; line-height: 1.8; margin: 10px 0; }
.notice { padding: 10px 12px; border-radius: 6px; background: #ad8c3010; margin: 12px 0; font-size: 12px; line-height: 1.7; }
.danger { color: #bd4450; background: #bd44500a; }
code, .error-code { font-size: 11px; overflow-wrap: anywhere; }
.target-list, .delivery-history, .part-list { list-style: none; margin: 0; padding: 0; }
.target-list > li, .delivery-history > li { border-top: 1px solid #8882; padding: 18px 0; overflow-wrap: anywhere; }
.target-list > li:first-child, .delivery-history > li:first-child { margin-top: 10px; }
.target-controls { display: flex; align-items: center; gap: 10px; margin: 12px 0; font-size: 12px; }
.channel { font-size: 11px; opacity: 0.65; margin-left: 8px; }
select { display: block; width: 100%; min-width: 0; padding: 9px 10px; background: transparent; border: 1px solid #8885; border-radius: 6px; margin: 6px 0 12px; font: inherit; }
select:focus-visible { outline: 2px solid #43856b; outline-offset: 2px; }
label { font-size: 12px; }
.form-actions, .batch-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.form-actions { margin-top: 18px; }
.saved { font-size: 12px; color: #43856b; margin-top: 10px; }
.batch-status { font-weight: 600; margin-top: 8px; }
summary { cursor: pointer; font-size: 12px; padding: 14px 0 8px; }
.part-list > li { padding: 12px; margin: 6px 0; background: #88800005; border: 1px solid #8882; border-radius: 6px; font-size: 13px; }
@media (max-width: 900px) { .delivery-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 480px) { .form-actions { flex-direction: column; } .delivery-heading { align-items: flex-start; } }
</style>
