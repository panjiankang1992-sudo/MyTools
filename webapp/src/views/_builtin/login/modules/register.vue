<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useCountDown, useLoading } from '@sa/hooks';
import { fetchRegister, fetchRegisterCode } from '@/service/api';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { REG_EMAIL, REG_PHONE, REG_PWD } from '@/constants/reg';
import { $t } from '@/locales';

defineOptions({
  name: 'Register'
});

const REG_REGISTER_USER_NAME = /^[a-zA-Z_][a-zA-Z0-9_]{2,19}$/;

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const { loading: codeLoading, startLoading: startCodeLoading, endLoading: endCodeLoading } = useLoading();
const { loading: submitLoading, startLoading: startSubmitLoading, endLoading: endSubmitLoading } = useLoading();
const { count, start, isCounting } = useCountDown(60);

const codeLabel = computed(() => {
  if (codeLoading.value) {
    return '';
  }

  if (isCounting.value) {
    return $t('page.login.codeLogin.reGetCode', { time: count.value });
  }

  return $t('page.login.codeLogin.getCode');
});

interface FormModel {
  username: string;
  email: string;
  phone: string;
  code: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  username: '',
  email: '',
  phone: '',
  code: '',
  password: '',
  confirmPassword: ''
});

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  const { formRules, createConfirmPwdRule, createRequiredRule } = useFormRules();

  return {
    username: [
      createRequiredRule($t('form.userName.required')),
      {
        pattern: REG_REGISTER_USER_NAME,
        message: $t('form.userName.invalid'),
        trigger: 'change'
      }
    ],
    email: formRules.email,
    phone: formRules.phone,
    code: formRules.code,
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});

function validateCodeFields() {
  if (!REG_REGISTER_USER_NAME.test(model.username)) {
    window.$message?.error?.($t('form.userName.invalid'));
    return false;
  }

  if (!REG_EMAIL.test(model.email)) {
    window.$message?.error?.($t('form.email.invalid'));
    return false;
  }

  if (!REG_PHONE.test(model.phone)) {
    window.$message?.error?.($t('form.phone.invalid'));
    return false;
  }

  return true;
}

async function handleSendCode() {
  if (isCounting.value || codeLoading.value || !validateCodeFields()) {
    return;
  }

  startCodeLoading();
  try {
    const { error } = await fetchRegisterCode({
      username: model.username,
      email: model.email,
      phone: model.phone
    });

    if (!error) {
      window.$message?.success($t('page.login.codeLogin.sendCodeSuccess'));
      start();
    }
  } finally {
    endCodeLoading();
  }
}

async function handleSubmit() {
  await validate();

  if (!REG_PWD.test(model.password)) {
    return;
  }

  startSubmitLoading();
  try {
    const { error } = await fetchRegister({
      username: model.username,
      email: model.email,
      phone: model.phone,
      password: model.password,
      verificationCode: model.code
    });

    if (!error) {
      window.$message?.success($t('page.login.register.success'));
      toggleLoginModule('pwd-login');
    }
  } finally {
    endSubmitLoading();
  }
}
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="username">
      <NInput v-model:value="model.username" :placeholder="$t('page.login.common.userNamePlaceholder')" />
    </NFormItem>
    <NFormItem path="email">
      <NInput v-model:value="model.email" :placeholder="$t('page.login.common.emailPlaceholder')" />
    </NFormItem>
    <NFormItem path="phone">
      <NInput v-model:value="model.phone" :placeholder="$t('page.login.common.phonePlaceholder')" />
    </NFormItem>
    <NFormItem path="code">
      <div class="w-full flex-y-center gap-16px">
        <NInput v-model:value="model.code" :placeholder="$t('page.login.common.codePlaceholder')" />
        <NButton class="min-w-140px shrink-0" size="large" :disabled="isCounting" :loading="codeLoading" @click="handleSendCode">
          {{ codeLabel }}
        </NButton>
      </div>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      />
    </NFormItem>
    <NFormItem path="confirmPassword">
      <NInput
        v-model:value="model.confirmPassword"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
      />
    </NFormItem>
    <NSpace vertical :size="18" class="w-full">
      <NButton type="primary" size="large" round block :loading="submitLoading" @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
      <NButton size="large" round block @click="toggleLoginModule('pwd-login')">
        {{ $t('page.login.common.back') }}
      </NButton>
    </NSpace>
  </NForm>
</template>

<style scoped></style>
