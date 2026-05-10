<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue';
import { loginModuleRecord } from '@/constants/app';
import { useAuthStore } from '@/store/modules/auth';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';
import { localStg } from '@/utils/storage';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
}

const rememberMe = ref(false);

const model: FormModel = reactive({
  userName: '',
  password: ''
});

onMounted(() => {
  const savedUserName = localStg.get('rememberedUserName');
  const savedPassword = localStg.get('rememberedPassword');

  if (savedUserName && savedPassword) {
    model.userName = savedUserName;
    model.password = savedPassword;
    rememberMe.value = true;
  }
});

function handleRememberMeChange(checked: boolean) {
  if (!checked) {
    localStg.remove('rememberedUserName');
    localStg.remove('rememberedPassword');
  }
}

async function handleSubmit() {
  await validate();

  if (rememberMe.value) {
    localStg.set('rememberedUserName', model.userName);
    localStg.set('rememberedPassword', model.password);
  } else {
    localStg.remove('rememberedUserName');
    localStg.remove('rememberedPassword');
  }

  await authStore.login(model.userName, model.password);
}

type AccountKey = 'super' | 'admin' | 'user';

interface Account {
  key: AccountKey;
  label: string;
  userName: string;
  password: string;
}

const accounts = computed<Account[]>(() => [
  {
    key: 'super',
    label: $t('page.login.pwdLogin.superAdmin'),
    userName: 'Super',
    password: '123456'
  },
  {
    key: 'admin',
    label: $t('page.login.pwdLogin.admin'),
    userName: 'Admin',
    password: '123456'
  },
  {
    key: 'user',
    label: $t('page.login.pwdLogin.user'),
    userName: 'User',
    password: '123456'
  }
]);

async function handleAccountLogin(account: Account) {
  if (rememberMe.value) {
    localStg.set('rememberedUserName', account.userName);
    localStg.set('rememberedPassword', account.password);
  }

  await authStore.login(account.userName, account.password);
}

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd
  };
});
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')" />
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      />
    </NFormItem>
    <NSpace vertical :size="24">
      <div class="flex-y-center justify-between">
        <NCheckbox v-model:checked="rememberMe" @update:checked="handleRememberMeChange">
          {{ $t('page.login.pwdLogin.rememberMe') }}
        </NCheckbox>
      </div>
      <NButton type="primary" size="large" round block :loading="authStore.loginLoading" @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
    </NSpace>
  </NForm>
</template>

<style scoped></style>
