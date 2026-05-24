declare namespace Api {
  namespace User {
    interface UserItem {
      id: number;
      username: string;
      nickname: string | null;
      avatar: string | null;
      email: string;
      phone: string;
      gender: number;
      birthday: string | null;
      address: string | null;
      hobbies: string | null;
      signature: string | null;
      role: string;
      status: string;
      registerTime: string;
      lastLoginTime: string;
    }

    interface UserProfile {
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
      registerTime: string;
      lastLoginTime: string;
    }
  }

  namespace Role {
    interface RoleItem {
      /** 角色ID，后端为 Snowflake ID（19位），前端统一用 string 避免精度丢失 */
      id: string;
      roleName: string;
      roleCode: string;
      description: string;
      status: string;
    }
  }
}
