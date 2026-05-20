<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { fetchChangePassword, fetchUpdateProfile, fetchWebdavAccount, updateWebdavAccount } from '@/service/api/user';
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
  NInputGroup,
  NGrid,
  NGridItem as NGi
} from 'naive-ui';

defineOptions({ name: 'UserProfile' });

const message = useMessage();
const authStore = useAuthStore();
const { loading: saveLoading, startLoading: startSave, endLoading: endSave } = useLoading();
const { loading: pwdLoading, startLoading: startPwdLoad, endLoading: endPwdLoad } = useLoading();
const { loading: webdavLoading, startLoading: startWebdav, endLoading: endWebdav } = useLoading();

// 头像预览
const avatarPreview = ref<string | null>(null);
const showAvatarModal = ref(false);
const avatarInputRef = ref<HTMLInputElement | null>(null);

// 编辑状态
const isEditing = ref(false);

// WebDAV 编辑状态
const webdavEditing = ref(false);
const webdavForm = reactive({
  type: 'jianguoyun',
  url: '',
  username: '',
  password: ''
});
const originalWebdavForm = reactive({
  type: 'jianguoyun',
  url: '',
  username: '',
  password: ''
});

// 性别选项
const genderOptions = [
  { label: '未知', value: 0 },
  { label: '男', value: 1 },
  { label: '女', value: 2 }
];

// WebDAV 类型选项
const webdavTypeOptions = [
  { label: '坚果云', value: 'jianguoyun' },
  { label: 'Nextcloud', value: 'nextcloud' },
  { label: 'ownCloud', value: 'owncloud' },
  { label: '群晖/NAS', value: 'synology' },
  { label: 'Alist', value: 'alist' },
  { label: 'S3/WebDAV网关', value: 's3' },
  { label: '自定义', value: 'custom' }
];

// 个人信息表单（初始为空，等 userInfo 加载后同步）
const profileForm = reactive({
  nickname: '',
  avatar: '',
  email: '',
  phone: '',
  gender: 0,
  birthday: null as number | null,
  address: '',
  hobbies: '',
  signature: ''
});

// 原始值用于比较
const originalForm = reactive({ ...profileForm });

// 监听 authStore.userInfo.id，等数据加载完成后同步到表单
watch(
  () => authStore.userInfo.id,
  (userId) => {
    if (userId) {
      const u = authStore.userInfo;
      profileForm.nickname = u.nickname || '';
      profileForm.avatar = u.avatar || '';
      profileForm.email = u.email || '';
      profileForm.phone = u.phone || '';
      profileForm.gender = u.gender || 0;
      profileForm.birthday = u.birthday ? new Date(u.birthday).getTime() : null;
      profileForm.address = u.address || '';
      profileForm.hobbies = u.hobbies || '';
      profileForm.signature = u.signature || '';
      Object.assign(originalForm, profileForm);
      loadWebdav();
    }
  },
  { immediate: true }
);

// 是否有变更
const hasChanges = computed(() => {
  return JSON.stringify(profileForm) !== JSON.stringify(originalForm);
});

// 用户数据是否已加载
const userDataLoaded = computed(() => Boolean(authStore.userInfo.id));

// 打开头像选择
function openAvatarSelect() {
  avatarInputRef.value?.click();
}

// 处理头像文件选择
async function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files && input.files[0]) {
    const file = input.files[0];
    try {
      const dataUrl = await compressImage(file, 200, 0.8);
      avatarPreview.value = dataUrl;
      profileForm.avatar = dataUrl;
      showAvatarModal.value = true;
    } catch {
      message.error('头像文件读取失败');
    }
  }
  input.value = '';
}

/**
 * 压缩图片：限制最大尺寸，按比例缩放后转为 JPEG base64
 */
function compressImage(file: File, maxSize: number, quality: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      const src = e.target?.result as string;
      const img = new Image();
      img.onload = () => {
        let { width, height } = img;
        if (width > maxSize || height > maxSize) {
          if (width > height) {
            height = Math.round((height * maxSize) / width);
            width = maxSize;
          } else {
            width = Math.round((width * maxSize) / height);
            height = maxSize;
          }
        }
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext('2d')!;
        ctx.drawImage(img, 0, 0, width, height);
        resolve(canvas.toDataURL('image/jpeg', quality));
      };
      img.onerror = reject;
      img.src = src;
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
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
    const profileData: any = {
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
    const { data } = await fetchUpdateProfile(profileData);
    if (data) {
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
      // 保存成功后同步 originalForm（确保 hasChanges 正确）
      const u = authStore.userInfo;
      originalForm.nickname = u.nickname || '';
      originalForm.avatar = u.avatar || '';
      originalForm.email = u.email || '';
      originalForm.phone = u.phone || '';
      originalForm.gender = u.gender || 0;
      originalForm.birthday = u.birthday ? new Date(u.birthday).getTime() : null;
      originalForm.address = u.address || '';
      originalForm.hobbies = u.hobbies || '';
      originalForm.signature = u.signature || '';
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

// 加载 WebDAV 账户
async function loadWebdav() {
  try {
    const data = await fetchWebdavAccount();
    if (data) {
      webdavForm.type = data.type || 'jianguoyun';
      webdavForm.url = data.url || '';
      webdavForm.username = data.username || '';
      webdavForm.password = '';
      originalWebdavForm.type = webdavForm.type;
      originalWebdavForm.url = webdavForm.url;
      originalWebdavForm.username = webdavForm.username;
      originalWebdavForm.password = '';
    }
  } catch {
    // 未配置时不报错
  }
}

// 保存 WebDAV 账户
async function saveWebdav() {
  try {
    startWebdav();
    await updateWebdavAccount({
      type: webdavForm.type,
      url: webdavForm.url,
      username: webdavForm.username,
      password: webdavForm.password || undefined
    });
    originalWebdavForm.type = webdavForm.type;
    originalWebdavForm.url = webdavForm.url;
    originalWebdavForm.username = webdavForm.username;
    originalWebdavForm.password = '';
    webdavForm.password = '';
    webdavEditing.value = false;
    message.success('保存成功');
  } catch (error: any) {
    const backendMsg = error?.response?.data?.message;
    message.error(backendMsg || '保存失败');
  } finally {
    endWebdav();
  }
}

// 取消 WebDAV 编辑
function cancelWebdavEdit() {
  webdavForm.type = originalWebdavForm.type;
  webdavForm.url = originalWebdavForm.url;
  webdavForm.username = originalWebdavForm.username;
  webdavForm.password = '';
  webdavEditing.value = false;
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
  if (passwordForm.newPassword.length < 6 || passwordForm.newPassword.length > 20) {
    message.error('新密码长度为6-20位');
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
      <NCard v-if="userDataLoaded" :bordered="false" class="mb-4">
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
              style="position:absolute;width:0;height:0;opacity:0;overflow:hidden;"
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

      <!-- WebDAV 信息维护 -->
      <NCard :bordered="false" class="mb-4">
        <template #header>
          <div class="flex justify-between items-center">
            <span class="text-lg font-semibold">WebDAV 信息维护</span>
            <NSpace>
              <NButton v-if="!webdavEditing" type="primary" @click="webdavEditing = true">
                编辑
              </NButton>
              <template v-else>
                <NButton @click="cancelWebdavEdit">取消</NButton>
                <NButton type="primary" :loading="webdavLoading" @click="saveWebdav">
                  保存
                </NButton>
              </template>
            </NSpace>
          </div>
        </template>

        <NGrid :x-gap="16" :cols="2">
          <NGi>
            <NFormItem label="类型">
              <NSelect
                v-model:value="webdavForm.type"
                :options="webdavTypeOptions"
                :disabled="!webdavEditing"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem label="地址">
              <NInput
                v-model:value="webdavForm.url"
                :disabled="!webdavEditing"
                placeholder="https://dav.example.com/dav/"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem label="用户名">
              <NInput
                v-model:value="webdavForm.username"
                :disabled="!webdavEditing"
                placeholder="请输入用户名"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem label="密码">
              <NInput
                v-model:value="webdavForm.password"
                type="password"
                :disabled="!webdavEditing"
                show-password-on="click"
                placeholder="已设置则留空"
              />
            </NFormItem>
          </NGi>
        </NGrid>
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
            placeholder="6-20位密码"
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
