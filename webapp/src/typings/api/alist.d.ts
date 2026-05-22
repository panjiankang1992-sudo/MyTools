declare namespace Api {
  namespace Alist {
    interface AlistFileItem {
      name: string;
      size: number;
      is_dir: boolean;
      modified: string;
      created: string;
      type: number;
      thumb?: string;
      sign?: string;
      raw_url?: string;
    }

    interface AlistListResponse {
      content: AlistFileItem[];
      total: number;
      provider: string;
      readme: string;
      header: string;
      write: boolean;
    }

    interface AlistLoginRequest {
      username: string;
      password: string;
    }

    interface AlistLoginResponse {
      token: string;
      device_key: string;
    }

    interface AlistRawUrlResponse {
      name: string;
      size: number;
      is_dir: boolean;
      raw_url: string;
      sign: string;
    }
  }
}
