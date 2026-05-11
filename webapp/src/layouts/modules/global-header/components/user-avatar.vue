<script setup lang="ts">
import { computed } from 'vue';
import type { VNode } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useSvgIcon } from '@/hooks/common/icon';
import { $t } from '@/locales';

defineOptions({
  name: 'UserAvatar'
});

const authStore = useAuthStore();
const { routerPushByKey, toLogin } = useRouterPush();
const { SvgIconVNode } = useSvgIcon();

function loginOrRegister() {
  toLogin();
}

// 显示名称：优先使用昵称，其次使用用户名
const displayName = computed(() => {
  return authStore.userInfo.nickname || authStore.userInfo.username || '用户';
});

// 显示头像：优先使用用户头像，其次使用默认头像
const avatarSrc = computed(() => {
  return authStore.userInfo.avatar || '/favicon.svg';
});

type DropdownKey = 'profile' | 'logout';

type DropdownOption =
  | {
      key: DropdownKey;
      label: string;
      icon?: () => VNode;
    }
  | {
      type: 'divider';
      key: string;
    };

const options = computed(() => {
  const opts: DropdownOption[] = [
    {
      label: $t('page.profile.title') || '个人中心',
      key: 'profile',
      icon: SvgIconVNode({ icon: 'ph:user-circle', fontSize: 18 })
    },
    {
      type: 'divider',
      key: 'divider'
    },
    {
      label: $t('common.logout'),
      key: 'logout',
      icon: SvgIconVNode({ icon: 'ph:sign-out', fontSize: 18 })
    }
  ];

  return opts;
});

function logout() {
  window.$dialog?.info({
    title: $t('common.tip'),
    content: $t('common.logoutConfirm'),
    positiveText: $t('common.confirm'),
    negativeText: $t('common.cancel'),
    onPositiveClick: () => {
      authStore.resetStore();
    }
  });
}

function handleDropdown(key: DropdownKey) {
  if (key === 'logout') {
    logout();
  } else if (key === 'profile') {
    routerPushByKey('profile');
  } else {
    routerPushByKey(key);
  }
}
</script>

<template>
  <NButton v-if="!authStore.isLogin" quaternary @click="loginOrRegister">
    {{ $t('page.login.common.loginOrRegister') }}
  </NButton>
  <NDropdown v-else placement="bottom" trigger="click" :options="options" @select="handleDropdown">
    <div>
      <ButtonIcon>
        <NAvatar
          v-if="avatarSrc"
          class="mr-8px"
          :src="avatarSrc"
          :fallback-src="'/favicon.svg'"
          round
          :size="28"
        />
        <NAvatar v-else class="mr-8px" round :size="28">
          {{ displayName.charAt(0).toUpperCase() }}
        </NAvatar>
        <span class="text-16px font-medium">{{ displayName }}</span>
      </ButtonIcon>
    </div>
  </NDropdown>
</template>

<style scoped></style>
