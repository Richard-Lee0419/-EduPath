import { apiPost } from './http';
import type { LoginPayload, RegisterPayload, ReviewRole, User } from '../types';
import type { LoginResult } from '@/types/api';
import { authStorage, clearReviewContext, clearStoredSession, EXPIRES_KEY, REFRESH_KEY,
  reviewCode, TOKEN_KEY, USER_KEY } from './session';

type BackendUser = LoginResult['user'];

const toUser = (backendUser: BackendUser, expiresAt?: string): User => ({
  id: backendUser.id,
  username: backendUser.username,
  email: backendUser.email,
  role: backendUser.role,
  review: backendUser.review,
  name: backendUser.review
    ? (backendUser.role === 'teacher' ? '评委教师' : '评委学生')
    : backendUser.email || backendUser.username,
  lastLoginAt: expiresAt,
});

const persistSession = (session: LoginResult): User => {
  const user = toUser(session.user, session.expires_at);
  if (!user.review) clearReviewContext();
  const storage = authStorage();
  storage.setItem(TOKEN_KEY, session.token);
  if (session.refresh_token) storage.setItem(REFRESH_KEY, session.refresh_token);
  else storage.removeItem(REFRESH_KEY);
  storage.setItem(EXPIRES_KEY, session.expires_at);
  storage.setItem(USER_KEY, JSON.stringify(user));
  return user;
};

export const getStoredUser = (): User | null => {
  const storage = authStorage();
  const token = storage.getItem(TOKEN_KEY);
  const raw = storage.getItem(USER_KEY);
  if (!token || !raw) return null;

  try {
    const user = JSON.parse(raw) as User;
    if (user.review && Date.parse(storage.getItem(EXPIRES_KEY) ?? '') <= Date.now()) {
      clearStoredSession(storage);
      return null;
    }
    return user;
  } catch {
    clearStoredSession(storage);
    return null;
  }
};

export const login = async (payload: LoginPayload): Promise<User> => {
  const session = await apiPost<LoginResult>('/auth/login', {
    username: payload.username,
    password: payload.password,
  }, { anonymous: true });
  return persistSession(session);
};

export const register = async (payload: RegisterPayload): Promise<User> => {
  const session = await apiPost<LoginResult>('/auth/register', {
    email: payload.email,
    password: payload.password,
    name: payload.name,
    teacher_invite_code: payload.teacherInviteCode,
  }, { anonymous: true });
  return persistSession(session);
};

export const enterReview = async (role: ReviewRole): Promise<User> => {
  const code = reviewCode();
  if (!code) throw new Error('请打开评委专属链接');
  const session = await apiPost<LoginResult>('/auth/review', { code, role }, { anonymous: true });
  return persistSession(session);
};

export const verifyCurrentUser = async (): Promise<User> => {
  const backendUser = await apiPost<BackendUser>('/auth/verify');
  const storage = authStorage();
  const user = toUser(backendUser, storage.getItem(EXPIRES_KEY) ?? undefined);
  storage.setItem(USER_KEY, JSON.stringify(user));
  return user;
};

export const logout = async () => {
  const storage = authStorage();
  try {
    if (storage.getItem(TOKEN_KEY)) {
      await apiPost<void>('/auth/logout');
    }
  } finally {
    clearStoredSession(storage);
  }
};
