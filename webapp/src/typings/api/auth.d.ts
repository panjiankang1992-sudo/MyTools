declare namespace Api {
  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      userId: number;
      username: string;
      role: string;
      accessToken: string;
      refreshToken: string;
      expiresIn: number;
    }

    interface UserInfo {
      id: number;
      username: string;
      email: string;
      phone: string;
      gender: number;
      role: string;
      status: string;
      lastLoginTime: string;
    }
  }
}
