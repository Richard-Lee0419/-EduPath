export const appPath = (path: string) => import.meta.env.BASE_URL + path.replace(/^\//, '');

export const localPath = (pathname: string) => {
  const base = import.meta.env.BASE_URL;
  return pathname.startsWith(base) ? '/' + pathname.slice(base.length) : pathname;
};
