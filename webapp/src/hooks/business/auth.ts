import { useAuthStore } from '@/store/modules/auth';

export function useAuth() {
  const authStore = useAuthStore();

  function hasAuth(codes: string | string[]) {
    if (!authStore.isLogin) {
      return false;
    }

    // For now, just return true if logged in, since buttons are not in the backend model
    return true;
  }

  return {
    hasAuth
  };
}
