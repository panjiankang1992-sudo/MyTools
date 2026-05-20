declare namespace Api {
  namespace CloudFile {
    interface CloudFileItem {
      name: string;
      path: string;
      isDirectory: boolean;
      size: number;
      contentType: string | null;
      lastModified: string | null;
      etag: string | null;
    }

    interface CloudFileListResponse {
      path: string;
      items: CloudFileItem[];
    }

    interface FileOperationResponse {
      name: string;
      path: string;
      size: number;
      lastModified: string;
    }
  }
}
