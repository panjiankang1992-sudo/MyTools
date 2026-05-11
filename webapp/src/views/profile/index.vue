<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { fetchChangePassword, fetchUpdateProfile } from '@/service/api/user';
import { useLoading } from '@sa/hooks';
import { $t } from '@/locales';
import {
  NButton,
  NCard,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NSpace,
  NDatePicker,
  NImage,
  NImageGroup,
  useMessage,
  NModal,
  NInputGroup
} from 'naive-ui';

defineOptions({ name: 'UserProfile' });

const message = useMessage();
const authStore = useAuthStore();
const { loading: saveLoading, startLoading: startSave, endLoading: endSave } = useLoading();
const { loading: pwdLoading, startLoading: startPwdLoad, endLoading: endPwdLoad } = useLoading();

// 头像预览
const avatarPreview = ref<string | null>(null);
const showAvatarModal = ref(false);
const avatarInputRef = ref<HTMLInputElement | null>(null);

// 编辑状态
const isEditing = ref(false);

// 性别选项
const genderOptions = [
  { label: '未知', value: 0 },
  { label: '男', value: 1 },
  { label: '女', value: 2 }
];

// 个人信息表单
const profileForm = reactive({
  nickname: authStore.userInfo.nickname || '',
  avatar: authStore.userInfo.avatar || '',
  email: authStore.userInfo.email || '',
  phone: authStore.userInfo.phone || '',
  gender: authStore.userInfo.gender || 0,
  birthday: authStore.userInfo.birthday ? new Date(authStore.userInfo.birthday).getTime() : null,
  address: authStore.userInfo.address || '',
  hobbies: authStore.userInfo.hobbies || '',
  signature: authStore.userInfo.signature || ''
});

// 原始值用于比较
const originalForm = reactive({ ...profileForm });

// 是否有变更
const hasChanges = computed(() => {
  return JSON.stringify(profileForm) !== JSON.stringify(originalForm);
});

// 打开头像选择
function openAvatarSelect() {
  avatarInputRef.value?.click();
}

// 处理头像文件选择
function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files && input.files[0]) {
    const file = input.files[0];
    const reader = new FileReader();
    reader.onload = (e) => {
      avatarPreview.value = e.target?.result as string;
      profileForm.avatar = avatarPreview.value;
      showAvatarModal.value = true;
    };
    reader.readAsDataURL(file);
  }
  input.value = '';
}

// 确认头像裁剪/选择
function confirmAvatar() {
  showAvatarModal.value = false;
}

// 取消头像选择
function cancelAvatar() {
  avatarPreview.value = null;
  profileForm.avatar = authStore.userInfo.avatar || '';
  showAvatarModal.value = false;
}

// 保存个人信息
async function saveProfile() {
  try {
    startSave();
    const data: any = {
      nickname: profileForm.nickname || null,
      avatar: profileForm.avatar || null,
      email: profileForm.email || null,
      phone: profileForm.phone || null,
      gender: profileForm.gender,
      birthday: profileForm.birthday ? new Date(profileForm.birthday).toISOString().split('T')[0] : null,
      address: profileForm.address || null,
      hobbies: profileForm.hobbies || null,
      signature: profileForm.signature || null
    };
    const result = await fetchUpdateProfile(data);
    if (result) {
      // 更新本地 store
      Object.assign(authStore.userInfo, {
        nickname: profileForm.nickname,
        avatar: profileForm.avatar,
        email: profileForm.email,
        phone: profileForm.phone,
        gender: profileForm.gender,
        birthday: profileForm.birthday ? new Date(profileForm.birthday).toISOString().split('T')[0] : null,
        address: profileForm.address,
        hobbies: profileForm.hobbies,
        signature: profileForm.signature
      });
      Object.assign(originalForm, profileForm);
      isEditing.value = false;
      message.success('保存成功');
    }
  } catch (error: any) {
    const backendMsg = error?.response?.data?.message;
    message.error(backendMsg || '保存失败');
  } finally {
    endSave();
  }
}

// 取消编辑
function cancelEdit() {
  Object.assign(profileForm, originalForm);
  avatarPreview.value = null;
  isEditing.value = false;
}

// 修改密码
const showPasswordModal = ref(false);
const passwordForm = reactive({
  oldPassword: '',
  newPassword: ''
});
const confirmPassword = ref('');

function openPasswordModal() {
  passwordForm.oldPassword = '';
  passwordForm.newPassword = '';
  confirmPassword.value = '';
  showPasswordModal.value = true;
}

async function handleChangePassword() {
  if (!passwordForm.oldPassword) {
    message.error('请输入旧密码');
    return;
  }
  if (!passwordForm.newPassword) {
    message.error('请输入新密码');
    return;
  }
  if (passwordForm.newPassword.length < 8) {
    message.error('新密码至少8位');
    return;
  }
  if (!/(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/.test(passwordForm.newPassword)) {
    message.error('新密码必须包含大小写字母和数字');
    return;
  }
  if (passwordForm.newPassword !== confirmPassword.value) {
    message.error('两次输入的密码不一致');
    return;
  }

  try {
    startPwdLoad();
    await fetchChangePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    });
    message.success('密码修改成功');
    showPasswordModal.value = false;
  } catch (error: any) {
    const backendMsg = error?.response?.data?.message;
    message.error(backendMsg || '密码修改失败');
  } finally {
    endPwdLoad();
  }
}
</script>

<template>
  <div class="profile-container p-6">
    <div class="max-w-4xl mx-auto">
      <NCard :bordered="false" class="mb-4">
        <template #header>
          <div class="flex justify-between items-center">
            <span class="text-lg font-semibold">个人信息</span>
            <NSpace>
              <NButton v-if="!isEditing" type="primary" @click="isEditing = true">
                编辑资料
              </NButton>
              <template v-else>
                <NButton @click="cancelEdit">取消</NButton>
                <NButton type="primary" :loading="saveLoading" @click="saveProfile">
                  保存
                </NButton>
              </template>
            </NSpace>
          </div>
        </template>

        <div class="flex flex-col md:flex-row gap-6">
          <!-- 头像区域 -->
          <div class="flex flex-col items-center">
            <div
              class="w-32 h-32 rounded-full overflow-hidden border-2 border-gray-200 cursor-pointer hover:border-primary transition-colors"
              @click="isEditing && openAvatarSelect()"
            >
              <img
                v-if="profileForm.avatar"
                :src="profileForm.avatar"
                class="w-full h-full object-cover"
                alt="头像"
              />
              <div v-else class="w-full h-full bg-gray-100 flex items-center justify-center text-4xl text-gray-400">
                {{ (profileForm.nickname || authStore.userInfo.username || '用户').charAt(0).toUpperCase() }}
              </div>
            </div>
            <input
              ref="avatarInputRef"
              type="file"
              accept="image/*"
              class="hidden"
              @change="handleAvatarChange"
            />
            <NButton
              v-if="isEditing"
              text
              type="primary"
              class="mt-2"
              @click="openAvatarSelect"
            >
              更换头像
            </NButton>
          </div>

          <!-- 基本信息 -->
          <div class="flex-1">
            <NForm
              :model="profileForm"
              label-placement="left"
              label-width="80"
              :disabled="!isEditing"
            >
              <NFormItem label="用户名">
                <NInput :value="authStore.userInfo.username" disabled />
              </NFormItem>
              <NFormItem label="昵称">
                <NInput v-model:value="profileForm.nickname" placeholder="请输入昵称" />
              </NFormItem>
              <NFormItem label="性别">
                <NSelect
                  v-model:value="profileForm.gender"
                  :options="genderOptions"
                  placeholder="请选择性别"
                />
              </NFormItem>
              <NFormItem label="邮箱">
                <NInput v-model:value="profileForm.email" placeholder="请输入邮箱" />
              </NFormItem>
              <NFormItem label="手机">
                <NInput v-model:value="profileForm.phone" placeholder="请输入手机号" />
              </NFormItem>
              <NFormItem label="生日">
                <NDatePicker
                  v-model:value="profileForm.birthday"
                  type="date"
                  placeholder="请选择生日"
                  style="width: 100%"
                />
              </NFormItem>
              <NFormItem label="地址">
                <NInput v-model:value="profileForm.address" placeholder="请输入地址" />
              </NFormItem>
              <NFormItem label="爱好">
                <NInput
                  v-model:value="profileForm.hobbies"
                  type="textarea"
                  placeholder="请输入爱好"
                  :rows="2"
                />
              </NFormItem>
              <NFormItem label="签名">
                <NInput
                  v-model:value="profileForm.signature"
                  type="textarea"
                  placeholder="请输入个人签名"
                  :rows="2"
                />
              </NFormItem>
            </NForm>
          </div>
        </div>
      </NCard>

      <!-- 账号安全 -->
      <NCard :bordered="false">
        <template #header>
          <span class="text-lg font-semibold">账号安全</span>
        </template>
        <div class="flex justify-between items-center">
          <div>
            <div class="font-medium">登录密码</div>
            <div class="text-gray-500 text-sm mt-1">定期更换密码可保护账户安全</div>
          </div>
          <NButton @click="openPasswordModal">修改密码</NButton>
        </div>
      </NCard>
    </div>

    <!-- 头像预览弹窗 -->
    <NModal v-model:show="showAvatarModal" preset="card" title="头像预览" style="width: 400px">
      <div class="flex flex-col items-center">
        <div class="w-48 h-48 rounded-full overflow-hidden border-4 border-gray-200">
          <img v-if="avatarPreview" :src="avatarPreview" class="w-full h-full object-cover" alt="头像预览" />
        </div>
        <div class="mt-4 text-gray-500 text-sm">确认使用此头像吗？</div>
        <NSpace class="mt-4">
          <NButton @click="cancelAvatar">取消</NButton>
          <NButton type="primary" @click="confirmAvatar">确认</NButton>
        </NSpace>
      </div>
    </NModal>

    <!-- 修改密码弹窗 -->
    <NModal
      v-model:show="showPasswordModal"
      preset="card"
      title="修改密码"
      style="width: 400px"
    >
      <NForm :model="passwordForm" label-placement="left" label-width="80">
        <NFormItem label="旧密码">
          <NInput
            v-model:value="passwordForm.oldPassword"
            type="password"
            placeholder="请输入旧密码"
            show-password-on="click"
          />
        </NFormItem>
        <NFormItem label="新密码">
          <NInput
            v-model:value="passwordForm.newPassword"
            type="password"
            placeholder="至少8位，包含大小写字母和数字"
            show-password-on="click"
          />
        </NFormItem>
        <NFormItem label="确认密码">
          <NInput
            v-model:value="confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            show-password-on="click"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showPasswordModal = false">取消</NButton>
          <NButton type="primary" :loading="pwdLoading" @click="handleChangePassword">
            确认修改
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.profile-container {
  min-height: calc(100vh - 120px);
  background: #f5f5f5;
}
</style>
