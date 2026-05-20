import { localStg } from '@/utils/storage';

/** Get token */
export function getToken() {
  // Use localStorage directly to avoid JSON.parse failing on plain JWT strings
  const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';
  return localStorage.getItem(storagePrefix + 'token') || '';
}

/** Clear auth storage */
export function clearAuthStorage() {
  const storagePrefix = import.meta.env.VITE_STORAGE_PREFIX || '';
  localStorage.removeItem(storagePrefix + 'token');
  localStorage.removeItem(storagePrefix + 'refreshToken');
}
