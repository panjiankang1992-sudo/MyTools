declare namespace Api {
  namespace Webdav {
    interface WebdavAccount {
      id: string;
      userId: string;
      type: string;
      name: string;
      url: string;
      username: string;
      passwordSet: boolean;
      isDefault: number;
      isActive: number;
    }

    interface CreateAccountRequest {
      type: string;
      name: string;
      url: string;
      username: string;
      password: string;
      isDefault: boolean;
    }

    interface UpdateAccountRequest {
      type: string;
      name: string;
      url: string;
      username: string;
      password: string;
      isDefault: boolean;
    }
  }
}
