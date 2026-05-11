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
      nickname: string | null;
      avatar: string | null;
      role: string;
      accessToken: string;
      refreshToken: string;
      expiresIn: number;
    }

    interface UserInfo {
      id: number;
      username: string;
      nickname: string | null;
      avatar: string | null;
      email: string | null;
      phone: string | null;
      gender: number;
      birthday: string | null;
      address: string | null;
      hobbies: string | null;
      signature: string | null;
      role: string;
      status: string;
      lastLoginTime: string;
    }
  }
}
