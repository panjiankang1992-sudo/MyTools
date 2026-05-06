declare namespace Api {
  namespace User {
    interface UserItem {
      id: number;
      username: string;
      email: string;
      phone: string;
      gender: number;
      role: string;
      status: string;
      registerTime: string;
      lastLoginTime: string;
    }
  }

  namespace Role {
    interface RoleItem {
      id: number;
      roleName: string;
      roleCode: string;
      description: string;
      status: string;
    }
  }
}