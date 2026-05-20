<script setup lang="ts">
import { ref, watch, computed, shallowRef } from 'vue';
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

const canEdit = computed(() => {
  if (!detail.value) return false;
  return isAdmin.value || detail.value.userId === authStore.userInfo.id;
});

const isAdmin = computed(() => authStore.userInfo.role === 'ADMIN');
const isEdit = computed(() => props.mode === 'publish' && !!props.appId);

// 详情数据
const detail = shallowRef<Api.AppMarket.AppDetail | null>(null);

// 历史版本
const versions = shallowRef<Api.AppMarket.AppVersion[]>([]);

// 上架/编辑表单
const form = ref({
  name: '',
  type: 'app' as 'app' | 'cli' | 'mcp' | 'skill',
  version: '1.0.0',
  content: '',
  installCmd: '',
  downloadUrl: ''
});

const submitting = ref(false);

watch(() => props.show, async (val) => {
  if (val) {
    if (props.mode === 'detail' && props.appId) {
      await loadDetail();
      await loadVersions();
    } else if (props.mode === 'publish') {
      if (props.appId) {
        await loadDetail();
        form.value = {
          name: detail.value?.name || '',
          type: (detail.value?.type as any) || 'app',
          version: detail.value?.version || '',
          content: detail.value?.content || '',
          installCmd: detail.value?.installCmd || '',
          downloadUrl: detail.value?.downloadUrl || ''
        };
      } else {
        form.value = { name: '', type: 'app', version: '1.0.0', content: '', installCmd: '', downloadUrl: '' };
        detail.value = null;
      }
      versions.value = [];
    }
  }
});

async function loadDetail() {
  if (!props.appId) return;
  startLoading();
  try {
    const { data } = await fetchGetAppDetail(props.appId);
    detail.value = data || null;
  } finally {
    endLoading();
  }
}

async function loadVersions() {
  if (!props.appId) return;
  const { data } = await fetchGetAppVersions(props.appId);
  versions.value = data || [];
}

async function handleSubmit() {
  if (!form.value.name.trim()) {
    message.warning('请填写应用名称');
    return;
  }
  if (!form.value.version.trim()) {
    message.warning('请填写版本号');
    return;
  }

  submitting.value = true;
  try {
    if (isEdit.value) {
      await fetchUpdateApp(props.appId!, {
        version: form.value.version,
        content: form.value.content || undefined,
        installCmd: form.value.installCmd || undefined,
        downloadUrl: form.value.downloadUrl || undefined
      });
      message.success('更新成功');
    } else {
      await fetchCreateApp({
        name: form.value.name,
        type: form.value.type,
        version: form.value.version,
        content: form.value.content || undefined,
        installCmd: form.value.installCmd || undefined,
        downloadUrl: form.value.downloadUrl || undefined
      });
      message.success('上架成功');
    }
    emit('close');
  } finally {
    submitting.value = false;
  }
}

function handleDownload() {
  if (!detail.value?.fileId) {
    message.warning('该应用暂无下载文件');
    return;
  }
  window.open(getFileDownloadUrl(detail.value.fileId), '_blank');
}

async function handleOffline() {
  if (!props.appId) return;
  await fetchOfflineApp(props.appId);
  message.success('下架成功');
  emit('close');
}

function handleClose() {
  emit('update:show', false);
  emit('close');
}

function getTypeTagType(type: string) {
  const map: Record<string, 'default' | 'success' | 'info' | 'warning' | 'error'> = {
    app: 'success', cli: 'info', mcp: 'warning', skill: 'error'
  };
  return map[type] || 'default';
}
</script>

<template>
  <NDrawer
    :show="props.show"
    display-directive="show"
    :width="560"
    @update:show="(val) => emit('update:show', val)"
  >
    <NDrawerContent
      :title="isEdit ? '编辑应用' : (props.mode === 'detail' ? '应用详情' : '上架新应用')"
      :native-scrollbar="false"
      closable
    >
      <!-- Detail 模式 -->
      <template v-if="props.mode === 'detail'">
        <NSpin :show="loading">
          <div v-if="detail">
            <NSpace vertical :size="12">
              <!-- 缩略图 -->
              <div style="text-align: center;">
                <NImage
                  v-if="detail.thumbnailUrl"
                  :src="detail.thumbnailUrl"
                  width="160"
                  height="160"
                  object-fit="cover"
                  style="border-radius: 8px;"
                />
                <div v-else style="width:160px;height:160px;margin:0 auto;background:#f5f5f5;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#999;font-size:13px;">
                  暂无缩略图
                </div>
              </div>

              <!-- 类型标签 -->
              <div style="text-align: center;">
                <NTag :type="getTypeTagType(detail.type)" size="large">
                  {{ detail.type?.toUpperCase() }}
                </NTag>
              </div>

              <!-- 基本信息 -->
              <NSpace vertical :size="8">
                <div><strong>名称：</strong>{{ detail.name }}</div>
                <div><strong>版本：</strong>{{ detail.version }}</div>
                <div><strong>发布人：</strong>{{ detail.userName }}（ID: {{ detail.userId }}）</div>
                <div><strong>上架时间：</strong>{{ detail.createdTime }}</div>
                <div v-if="detail.installCmd"><strong>安装命令：</strong><code style="background:#f5f5f5;padding:2px 6px;border-radius:4px;">{{ detail.installCmd }}</code></div>
                <div v-if="detail.downloadUrl">
                  <strong>外部链接：</strong>
                  <a :href="detail.downloadUrl" target="_blank" style="color:#18a058;word-break:break-all;">{{ detail.downloadUrl }}</a>
                </div>
                <div v-if="detail.fileName"><strong>文件：</strong>{{ detail.fileName }}
                  <span v-if="detail.fileSize" style="color:#999;">（{{ (detail.fileSize / 1024).toFixed(1) }} KB）</span>
                </div>
              </NSpace>

              <!-- 简介内容 -->
              <NDivider>应用简介</NDivider>
              <div
                v-if="detail.content"
                v-html="detail.content"
                style="line-height:1.8;color:#333;padding:4px 0;"
              />
              <NEmpty v-else description="暂无简介" />

              <!-- 历史版本 -->
              <NDivider>历史版本</NDivider>
              <NSpace v-if="versions.length" vertical :size="8">
                <div
                  v-for="v in versions"
                  :key="v.id"
                  style="padding:8px 12px;background:#f5f5f5;border-radius:4px;font-size:13px;display:flex;justify-content:space-between;align-items:center;"
                >
                  <span><strong>v{{ v.version }}</strong></span>
                  <span style="color:#999;">{{ v.createdTime }}</span>
                </div>
              </NSpace>
              <NEmpty v-else description="暂无历史版本" />
            </NSpace>
          </div>
        </NSpin>

        <!-- 操作按钮 -->
        <template #footer>
          <NSpace justify="center">
            <NButton @click="handleClose">关闭</NButton>
            <NButton type="info" @click="handleDownload" :disabled="!detail?.fileId">
              下载{{ detail?.fileName ? `（${detail.fileName}）` : '' }}
            </NButton>
            <NButton v-if="canEdit" type="warning" @click="emit('update:show', false); emit('close');">
              编辑
            </NButton>
            <NPopconfirm v-if="canEdit" @positive-click="handleOffline">
              <template #trigger>
                <NButton type="error">下架</NButton>
              </template>
              确定下架该应用？下架后可重新上架。
            </NPopconfirm>
          </NSpace>
        </template>
      </template>

      <!-- Publish 模式 -->
      <template v-else>
        <NForm label-placement="left" label-width="90">
          <NFormItem label="应用名称" required>
            <NInput
              v-model:value="form.name"
              placeholder="请输入应用名称"
              :disabled="isEdit"
            />
          </NFormItem>

          <NFormItem v-if="!isEdit" label="应用类型">
            <NSelect
              v-model:value="form.type"
              :options="[
                { label: 'App（富文本HTML）', value: 'app' },
                { label: 'CLI（二进制）', value: 'cli' },
                { label: 'MCP（JSON配置）', value: 'mcp' },
                { label: 'Skill（ZIP包）', value: 'skill' }
              ]"
            />
          </NFormItem>

          <NFormItem label="版本号" required>
            <NInput v-model:value="form.version" placeholder="如 1.0.0" />
          </NFormItem>

          <NFormItem label="应用简介">
            <div style="border:1px solid #d9d9d9;border-radius:4px;padding:8px;min-height:120px;">
              <div
                contenteditable="true"
                :innerHTML="form.content"
                @input="(e) => form.content = (e.target as HTMLElement).innerHTML"
                style="min-height:100px;outline:none;line-height:1.6;color:#333;"
              />
            </div>
            <div style="font-size:12px;color:#999;margin-top:4px;">
              支持HTML富文本格式（&lt;b&gt;、&lt;i&gt;、&lt;code&gt;、&lt;ul&gt;等）
            </div>
          </NFormItem>

          <NFormItem label="安装命令">
            <NInput v-model:value="form.installCmd" placeholder="如：npm install -g my-cli" />
          </NFormItem>

          <NFormItem label="外部下载链接">
            <NInput v-model:value="form.downloadUrl" placeholder="如有外部下载链接可填写" />
          </NFormItem>
        </NForm>

        <template #footer>
          <NSpace justify="center">
            <NButton @click="handleClose">取消</NButton>
            <NButton type="primary" :loading="submitting" @click="handleSubmit">
              {{ isEdit ? '保存更新' : '确认上架' }}
            </NButton>
          </NSpace>
        </template>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped></style>
