declare namespace Api {
  namespace AppMarket {
    interface AppItem {
      id: string;
      name: string;
      type: 'app' | 'cli' | 'mcp' | 'skill';
      version: string;
      thumbnailId: string | null;
      thumbnailUrl: string | null;
      contentPreview: string;
      status: 'PUBLISHED' | 'DRAFT';
      userId: number;
      userName: string;
      createdTime: string;
      updateTime: string;
    }

    interface AppDetail {
      id: string;
      name: string;
      type: string;
      version: string;
      thumbnailId: string | null;
      thumbnailUrl: string | null;
      content: string | null;
      installCmd: string | null;
      downloadUrl: string | null;
      status: string;
      userId: number;
      userName: string;
      createdTime: string;
      updateTime: string;
      fileId: string | null;
      fileName: string | null;
      fileSize: number | null;
      fileType: string | null;
      thumbnailPath: string | null;
      isOwner: boolean;
    }

    interface AppVersion {
      id: string;
      appId: string;
      version: string;
      content: string | null;
      fileId: string | null;
      createdTime: string;
    }

    interface CreateAppRequest {
      name: string;
      type: string;
      version: string;
      content?: string;
      installCmd?: string;
      downloadUrl?: string;
    }

    interface UpdateAppRequest {
      version: string;
      content?: string;
      installCmd?: string;
      downloadUrl?: string;
    }

    interface ListResponse {
      list: AppItem[];
      total: number;
      page: number;
      pageSize: number;
    }
  }
}
