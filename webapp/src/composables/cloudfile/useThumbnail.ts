import { onBeforeUnmount } from 'vue';
import { downloadCloudFile } from '@/service/api/cloudfile';

export function useThumbnail() {
  const thumbnailCache = new Map<string, string>();
  const blobUrls = new Set<string>();

  function isImageFile(name: string): boolean {
    const ext = name.split('.').pop()?.toLowerCase() || '';
    return ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico'].includes(ext);
  }

  function isVideoFile(name: string): boolean {
    const ext = name.split('.').pop()?.toLowerCase() || '';
    return ['mp4', 'avi', 'mov', 'mkv', 'flv', 'webm', 'wmv'].includes(ext);
  }

  function isMediaFile(name: string): boolean {
    return isImageFile(name) || isVideoFile(name);
  }

  function revokeAll() {
    blobUrls.forEach(url => {
      try {
        URL.revokeObjectURL(url);
      } catch {
        // ignore
      }
    });
    blobUrls.clear();
    thumbnailCache.clear();
  }

  async function getImageThumbnail(path: string): Promise<string> {
    const cached = thumbnailCache.get(path);
    if (cached) return cached;

    const { data: blob } = await downloadCloudFile(path);
    if (!blob) throw new Error('Download failed');

    const url = URL.createObjectURL(blob);
    blobUrls.add(url);
    thumbnailCache.set(path, url);
    return url;
  }

  async function extractVideoFrame(videoBlob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const videoUrl = URL.createObjectURL(videoBlob);
      const video = document.createElement('video');
      const canvas = document.createElement('canvas');

      video.preload = 'metadata';
      video.muted = true;
      video.playsInline = true;

      const cleanup = () => {
        URL.revokeObjectURL(videoUrl);
        video.remove();
        canvas.remove();
      };

      video.onloadeddata = () => {
        video.currentTime = Math.min(1, video.duration || 1);
      };

      video.onseeked = () => {
        canvas.width = video.videoWidth || 320;
        canvas.height = video.videoHeight || 180;
        const ctx = canvas.getContext('2d');
        if (ctx) {
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        }
        const dataUrl = canvas.toDataURL('image/jpeg', 0.7);
        cleanup();
        resolve(dataUrl);
      };

      video.onerror = () => {
        cleanup();
        reject(new Error('Video load failed'));
      };

      video.src = videoUrl;
    });
  }

  async function getVideoThumbnail(path: string): Promise<string> {
    const cached = thumbnailCache.get(path);
    if (cached) return cached;

    const { data: blob } = await downloadCloudFile(path);
    if (!blob) throw new Error('Download failed');

    const dataUrl = await extractVideoFrame(blob);
    thumbnailCache.set(path, dataUrl);
    return dataUrl;
  }

  onBeforeUnmount(() => revokeAll());

  return {
    getImageThumbnail,
    getVideoThumbnail,
    isImageFile,
    isVideoFile,
    isMediaFile,
    revokeAll
  };
}
