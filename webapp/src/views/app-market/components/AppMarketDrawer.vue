<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import {
  fetchGetAppDetail, fetchCreateApp, fetchUpdateApp,
  fetchGetAppVersions, fetchOfflineApp, getFileDownloadUrl
} from '@/service/api/appmarket';
import { useAuthStore } from '@/store/modules/auth';
import { useLoading } from '@sa/hooks';
import {
  NDrawer, NDrawerContent, NButton, NSpace, NTag, NImage,
  NInput, NSelect, NForm, NFormItem, NEmpty,
  NSpin, NDivider, NPopconfirm, useMessage
} from 'naive-ui';

const props = defineProps<{
  show: boolean;
  mode: 'detail' | 'publish';
  appId: string | null;
}>();

const emit = defineEmits<{
  (e: 'update:show', val: boolean): void;
  (e: 'close'): void;
}>();

const message = useMessage();
const authStore = useAuthStore();
const { loading, startLoading, endLoading } = useLoading();

const detail = ref<any>(null);
const versions = ref<any[]>([]);
const form = ref({ name: '', type: 'app', version: '1.0.0', content: '', installCmd: '', downloadUrl: '' });
const submitting = ref(false);

watch(() => props.show, async (val) => {
  if (!val) return;
  if (props.mode === 'detail' && props.appId) {
    startLoading();
    try {
      const res = await fetchGetAppDetail(props.appId);
      detail.value = res.data || null;
      const vr = await fetchGetAppVersions(props.appId);
      versions.value = vr.data || [];
    } finally {
      endLoading();
    }
  } else {
    if (props.appId) {
      const res = await fetchGetAppDetail(props.appId);
      if (res.data) {
        const d = res.data;
        form.value = { name: d.name || '', type: d.type || 'app', version: d.version || '', content: d.content || '', installCmd: d.installCmd || '', downloadUrl: d.downloadUrl || '' };
      }
    } else {
      form.value = { name: '', type: 'app', version: '1.0.0', content: '', installCmd: '', downloadUrl: '' };
    }
    detail.value = null;
    versions.value = [];
  }
});

async function handleSubmit() {
  if (!form.value.name.trim()) { message.warning('请填写应用名称'); return; }
  if (!form.value.version.trim()) { message.warning('请填写版本号'); return; }
  submitting.value = true;
  try {
    if (props.appId) {
      await fetchUpdateApp(props.appId, { version: form.value.version, content: form.value.content || undefined, installCmd: form.value.installCmd || undefined, downloadUrl: form.value.downloadUrl || undefined });
      message.success('更新成功');
    } else {
      await fetchCreateApp({ name: form.value.name, type: form.value.type, version: form.value.version, content: form.value.content || undefined, installCmd: form.value.installCmd || undefined, downloadUrl: form.value.downloadUrl || undefined });
      message.success('上架成功');
    }
    emit('close');
  } finally {
    submitting.value = false;
  }
}

function handleDownload() {
  const fid = detail.value?.fileId;
  if (!fid) { message.warning('该应用暂无下载文件'); return; }
  window.open(getFileDownloadUrl(fid), '_blank');
}

async function handleOffline() {
  if (!props.appId) return;
  await fetchOfflineApp(props.appId);
  message.success('下架成功');
  emit('close');
}

function onClose() { emit('update:show', false); emit('close'); }

const isEdit = computed(() => props.mode === 'publish' && !!props.appId);
const isAdmin = computed(() => authStore.userInfo.role === 'ADMIN');
const canEdit = computed(() => detail.value && (isAdmin.value || detail.value.userId === authStore.userInfo.id));
const title = computed(() => isEdit.value ? '编辑应用' : props.mode === 'detail' ? '应用详情' : '上架新应用');

function getTagType(t: string | undefined) {
  const m: Record<string, string> = { app: 'success', cli: 'info', mcp: 'warning', skill: 'error' };
  return m[t || ''] || 'default';
}
</script>

<template>
  <NDrawer :show="props.show" display-directive="show" :width="560" @update:show="(v: boolean) => emit('update:show', v)">
    <NDrawerContent :title="title" :native-scrollbar="false" closable>
      <NSpin :show="loading">
        <NSpace vertical :size="12">
          <div style="text-align:center">
            <NImage v-if="detail?.thumbnailUrl" :src="detail.thumbnailUrl" width="160" height="160" style="border-radius:8px" />
            <div v-else style="width:160px;height:160px;margin:0 auto;background:#f5f5f5;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#999">暂无缩略图</div>
          </div>
          <div style="text-align:center">
            <NTag :type="getTagType(detail?.type) as any" size="large">{{ (detail?.type || '').toUpperCase() }}</NTag>
          </div>
          <NSpace vertical :size="8">
            <div><strong>名称：</strong>{{ detail?.name }}</div>
            <div><strong>版本：</strong>{{ detail?.version }}</div>
            <div><strong>发布人：</strong>{{ detail?.userName }} ({{ detail?.userId }})</div>
            <div><strong>上架时间：</strong>{{ detail?.createdTime }}</div>
            <div v-if="detail?.installCmd"><strong>安装命令：</strong><code style="background:#f5f5f5;padding:2px 6px">{{ detail.installCmd }}</code></div>
            <div v-if="detail?.downloadUrl"><strong>外部链接：</strong><a :href="detail.downloadUrl" target="_blank" style="color:#18a058">{{ detail.downloadUrl }}</a></div>
            <div v-if="detail?.fileName"><strong>文件：</strong>{{ detail.fileName }}</div>
          </NSpace>
          <NDivider>应用简介</NDivider>
          <div v-if="detail?.content" v-html="detail.content" style="line-height:1.8" />
          <NEmpty v-else description="暂无简介" />
          <NDivider>历史版本</NDivider>
          <div v-if="versions.length">
            <div v-for="v in versions" :key="v.id" style="padding:8px;background:#f5f5f5;border-radius:4px;margin-bottom:8px;font-size:13px">
              <strong>v{{ v.version }}</strong> — {{ v.createdTime }}
            </div>
          </div>
          <NEmpty v-else description="暂无历史版本" />
        </NSpace>
      </NSpin>
      <template v-if="props.mode === 'detail'" #footer>
        <NSpace justify="center">
          <NButton @click="onClose">关闭</NButton>
          <NButton type="info" @click="handleDownload" :disabled="!detail?.fileId">下载{{ detail?.fileName ? `（${detail.fileName}）` : '' }}</NButton>
          <NButton v-if="canEdit" type="warning" @click="emit('close')">编辑</NButton>
          <NPopconfirm v-if="canEdit" @positive-click="handleOffline">
            <template #trigger><NButton type="error">下架</NButton></template>
            确定下架该应用？
          </NPopconfirm>
        </NSpace>
      </template>
      <template v-if="props.mode === 'publish'" #footer>
        <NForm label-placement="left" label-width="90">
          <NFormItem label="应用名称" required>
            <NInput v-model:value="form.name" placeholder="请输入应用名称" :disabled="isEdit" />
          </NFormItem>
          <NFormItem v-if="!isEdit" label="应用类型">
            <NSelect v-model:value="form.type" :options="[
              { label: 'App（富文本HTML）', value: 'app' },
              { label: 'CLI（二进制）', value: 'cli' },
              { label: 'MCP（JSON配置）', value: 'mcp' },
              { label: 'Skill（ZIP包）', value: 'skill' }
            ]" />
          </NFormItem>
          <NFormItem label="版本号" required>
            <NInput v-model:value="form.version" placeholder="如 1.0.0" />
          </NFormItem>
          <NFormItem label="应用简介">
            <NInput v-model:value="form.content" type="textarea" :rows="4" placeholder="请输入应用简介" />
          </NFormItem>
          <NFormItem label="安装命令">
            <NInput v-model:value="form.installCmd" placeholder="如：npm install -g my-cli" />
          </NFormItem>
          <NFormItem label="外部下载链接">
            <NInput v-model:value="form.downloadUrl" placeholder="如有外部下载链接可填写" />
          </NFormItem>
        </NForm>
        <NSpace justify="center">
          <NButton @click="onClose">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="handleSubmit">{{ isEdit ? '保存更新' : '确认上架' }}</NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>
