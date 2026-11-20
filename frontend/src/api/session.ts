export const USER_KEY = 'edupath_user';
export const TOKEN_KEY = 'edupath_token';
export const REFRESH_KEY = 'edupath_refresh_token';
export const EXPIRES_KEY = 'edupath_token_expires_at';
const REVIEW_CODE_KEY = 'edupath_review_code';

export const reviewCode = () => sessionStorage.getItem(REVIEW_CODE_KEY);

export const authStorage = () => reviewCode() ? sessionStorage : localStorage;

export const accessToken = () => authStorage().getItem(TOKEN_KEY);

export const clearStoredSession = (storage: Storage) => {
  for (const key of [USER_KEY, TOKEN_KEY, REFRESH_KEY, EXPIRES_KEY]) storage.removeItem(key);
};

export const clearReviewContext = () => {
  clearStoredSession(sessionStorage);
  sessionStorage.removeItem(REVIEW_CODE_KEY);
};

export const captureReviewLink = (): boolean => {
  if (location.pathname !== '/login') return false;
  const fragment = new URLSearchParams(location.hash.slice(1));
  if (!fragment.has('review')) return false;

  clearReviewContext();
  const code = fragment.get('review') ?? '';
  if (/^rv_[A-Za-z0-9_-]{43}$/.test(code)) {
    sessionStorage.setItem(REVIEW_CODE_KEY, code);
  }
  fragment.delete('review');
  const remaining = fragment.toString();
  history.replaceState(history.state, '', location.pathname + location.search + (remaining ? '#' + remaining : ''));
  return true;
};
