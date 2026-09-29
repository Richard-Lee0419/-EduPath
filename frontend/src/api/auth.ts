import { apiPost } from './http';
import type { LoginPayload, RegisterPayload, User } from '../types';
import type { LoginResult } from '@/types/api';

const USER_STORAGE_KEY = 'edupath_user';
const TOKEN_STORAGE_KEY = 'edupath_token';
const REFRESH_STORAGE_KEY = 'edupath_refresh_token';
const EXPIRES_STORAGE_KEY = 'edupath_token_expires_at';

type BackendUser = LoginResult['user'];

const toUser = (backendUser: BackendUser, expiresAt?: string): User => ({
  id: backendUser.id,
  username: backendUser.username,
  email: backendUser.email,
  role: backendUser.role,
  name: backendUser.email || backendUser.username,
  lastLoginAt: expiresAt,
});

const persistSession = (session: LoginResult): User => {
  const user = toUser(session.user, session.expires_at);
  localStorage.setItem(TOKEN_STORAGE_KEY, session.token);
  localStorage.setItem(REFRESH_STORAGE_KEY, session.refresh_token);
  localStorage.setItem(EXPIRES_STORAGE_KEY, session.expires_at);
  localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user));
  return user;
};

export const getStoredUser = (): User | null => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY);
  const raw = localStorage.getItem(USER_STORAGE_KEY);
  if (!token || !raw) return null;

  try {
    return JSON.parse(raw) as User;
  } catch {
    clearSession();
    return null;
  }
};

export const login = async (payload: LoginPayload): Promise<User> => {
  const session = await apiPost<LoginResult>('/auth/login', {
    username: payload.username,
    password: payload.password,
  });
  return persistSession(session);
};

export const register = async (payload: RegisterPayload): Promise<User> => {
  const session = await apiPost<LoginResult>('/auth/register', {
    email: payload.email,
    password: payload.password,
    name: payload.name,
  });
  return persistSession(session);
};

export const verifyCurrentUser = async (): Promise<User> => {
  const backendUser = await apiPost<BackendUser>('/auth/verify');
  const user = toUser(backendUser, localStorage.getItem(EXPIRES_STORAGE_KEY) ?? undefined);
  localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user));
  return user;
};

export const logout = async () => {
  try {
    if (localStorage.getItem(TOKEN_STORAGE_KEY)) {
      await apiPost<void>('/auth/logout');
    }
  } finally {
    clearSession();
  }
};

const clearSession = () => {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  localStorage.removeItem(REFRESH_STORAGE_KEY);
  localStorage.removeItem(EXPIRES_STORAGE_KEY);
  localStorage.removeItem(USER_STORAGE_KEY);
};
