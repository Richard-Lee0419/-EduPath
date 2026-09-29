import { defineStore } from "pinia";

type SessionUser = {
  id: number;
  username: string;
  role: "student" | "teacher" | "admin";
};

export const useSessionStore = defineStore("session", {
  state: () => ({
    token: localStorage.getItem("edupath_token") ?? "",
    user: null as SessionUser | null,
  }),
  actions: {
    setSession(token: string, user: SessionUser) {
      this.token = token;
      this.user = user;
      localStorage.setItem("edupath_token", token);
    },
    clearSession() {
      this.token = "";
      this.user = null;
      localStorage.removeItem("edupath_token");
    },
  },
});
