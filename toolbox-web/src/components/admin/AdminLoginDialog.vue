<script setup lang="ts">
import { NAlert, NButton, NFormItem, NInput, NModal, NSpace } from 'naive-ui';
import { computed, nextTick, ref, watch } from 'vue';
import { AdminApiError } from '../../services/admin-api';
import { loginErrorMessage } from '../../lib/admin-login-messages';
import { useAdminAuthStore } from '../../stores/admin-auth';

/**
 * 管理员登录表单。
 * 公共页面用弹窗，直达管理链接时由页面用内联形态复用同一组件与同一状态。
 * 登录失败保留账号、清空密码；取消不改动页面上的任何输入。
 */
// 注意：必须给 modal 显式默认值。Vue 对 Boolean 类型的 prop 会把"未传"转换为 false，
// 若不设默认值，公共页面会误判为内联形态并把登录表单直接渲染到页面上。
const props = withDefaults(defineProps<{
  /** 是否以弹窗形态展示；内联形态显式传 false。 */
  modal?: boolean;
  /** 弹窗是否可见。 */
  show?: boolean;
}>(), { modal: true, show: false });

const emit = defineEmits<{
  'update:show': [value: boolean];
  /** 登录成功。 */
  success: [];
}>();

const auth = useAdminAuthStore();
const account = ref('');
const password = ref('');
const submitting = ref(false);
const messageKey = ref('');
const accountInput = ref<InstanceType<typeof NInput> | null>(null);

const visible = computed({
  get: () => props.show ?? false,
  set: value => emit('update:show', value)
});

/** 把服务端文案键映射为可操作的中文提示，区分四类失败场景。 */
// 文案抽到 `lib/admin-login-messages`：本仓库没有组件挂载测试工具，
// 放在组件里的映射**测不到**，而它恰好是用户唯一能看到的失败原因。
const errorText = computed(() => loginErrorMessage(messageKey.value));

/** 打开时聚焦账号输入框，关闭时清理错误提示。 */
watch(
  () => props.show,
  async value => {
    if (value) {
      messageKey.value = '';
      await nextTick();
      accountInput.value?.focus();
    }
  }
);

/** 提交登录；失败时保留账号并清空密码。 */
async function submit(): Promise<void> {
  if (submitting.value) return;
  if (!account.value.trim() || !password.value) {
    messageKey.value = 'request.invalid';
    return;
  }
  submitting.value = true;
  messageKey.value = '';
  try {
    await auth.login(account.value.trim(), password.value);
    password.value = '';
    visible.value = false;
    emit('success');
  } catch (cause) {
    // 保留账号便于重试，清空密码避免留在输入框里。
    password.value = '';
    messageKey.value = cause instanceof AdminApiError ? cause.messageKey : 'request.failed';
  } finally {
    submitting.value = false;
  }
}

/** 取消：不改动当前页面状态。 */
function cancel(): void {
  password.value = '';
  messageKey.value = '';
  visible.value = false;
}
</script>

<template>
  <!-- 公共页面：弹窗形态。用两个明确分支而不是动态组件，避免 v-bind 展开导致弹窗内容泄漏到页面上。 -->
  <NModal
    v-if="modal !== false"
    :show="visible"
    preset="card"
    title="管理员登录"
    :style="{ width: '420px' }"
    :mask-closable="false"
    @update:show="(value: boolean) => (visible = value)"
  >
    <p class="login-note">使用 MyTools 管理员账号登录</p>
    <NFormItem label="用户名或邮箱" :show-feedback="false">
      <NInput
        ref="accountInput"
        v-model:value="account"
        :disabled="submitting"
        placeholder="用户名或邮箱"
        :input-props="{ 'aria-label': '用户名或邮箱' }"
        @keyup.enter="submit"
      />
    </NFormItem>
    <NFormItem label="密码" :show-feedback="false">
      <NInput
        v-model:value="password"
        type="password"
        show-password-on="click"
        :disabled="submitting"
        placeholder="密码"
        :input-props="{ 'aria-label': '密码' }"
        @keyup.enter="submit"
      />
    </NFormItem>
    <NAlert v-if="errorText" type="error" :show-icon="false" class="login-error">{{ errorText }}</NAlert>
    <NSpace justify="end" class="login-actions">
      <NButton quaternary :disabled="submitting" @click="cancel">取消</NButton>
      <NButton type="primary" :loading="submitting" :disabled="submitting" @click="submit">登录</NButton>
    </NSpace>
  </NModal>
  <!-- 直达管理链接：同一表单的内联形态。 -->
  <section v-else class="admin-login-inline" aria-label="管理员登录">
    <div class="inline-title">管理员登录</div>
    <p class="login-note">使用 MyTools 管理员账号登录</p>
    <NFormItem label="用户名或邮箱" :show-feedback="false">
      <NInput
        ref="accountInput"
        v-model:value="account"
        :disabled="submitting"
        placeholder="用户名或邮箱"
        :input-props="{ 'aria-label': '用户名或邮箱' }"
        @keyup.enter="submit"
      />
    </NFormItem>
    <NFormItem label="密码" :show-feedback="false">
      <NInput
        v-model:value="password"
        type="password"
        show-password-on="click"
        :disabled="submitting"
        placeholder="密码"
        :input-props="{ 'aria-label': '密码' }"
        @keyup.enter="submit"
      />
    </NFormItem>
    <NAlert v-if="errorText" type="error" :show-icon="false" class="login-error">{{ errorText }}</NAlert>
    <NSpace justify="end" class="login-actions">
      <NButton quaternary :disabled="submitting" @click="cancel">取消</NButton>
      <NButton type="primary" :loading="submitting" :disabled="submitting" @click="submit">登录</NButton>
    </NSpace>
  </section>
</template>

<style scoped>
.admin-login-inline {
  display: block;
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
  max-width: 420px;
}
.inline-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 4px;
}
.login-note {
  margin: 0 0 12px;
  color: var(--ink-muted, #666);
  font-size: 13px;
}
.login-error {
  margin-bottom: 12px;
}
.login-actions {
  margin-top: 4px;
}
</style>
