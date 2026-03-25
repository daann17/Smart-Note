export type SessionPayload = {
  token?: string;
  username: string;
  displayName?: string | null;
  role?: string | null;
};

const DEFAULT_ROLE = 'USER';

export const storeSession = (payload: SessionPayload) => {
  if (payload.token) {
    localStorage.setItem('token', payload.token);
  }

  localStorage.setItem('username', payload.username);
  localStorage.setItem('displayName', (payload.displayName || payload.username).trim());
  localStorage.setItem('role', (payload.role || DEFAULT_ROLE).toUpperCase());
};

export const clearSession = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('username');
  localStorage.removeItem('displayName');
  localStorage.removeItem('role');
};

export const getStoredRole = () => (localStorage.getItem('role') || DEFAULT_ROLE).toUpperCase();

export const isStoredAdmin = () => getStoredRole() === 'ADMIN';
