import { apiDelete, apiGet, apiGetBlob, apiPost, apiUpload } from './http';

export interface PersonalPdf {
  id: string;
  filename: string;
  page_count: number;
  uploaded_at: string;
}

export interface PersonalPdfAnswer {
  outcome: 'answered' | 'insufficient_evidence' | 'review_required';
  guidance: string;
  evidence: { page: number; excerpt: string }[];
}

const collection = '/tutor/personal-pdfs';
const documentPath = (id: string) => `${collection}/${encodeURIComponent(id)}`;

export const listPersonalPdfs = () => apiGet<PersonalPdf[]>(collection);

export function uploadPersonalPdf(file: File) {
  const data = new FormData();
  data.append('file', file);
  return apiUpload<PersonalPdf>(collection, data);
}

export const askPersonalPdf = (id: string, question: string) =>
  apiPost<PersonalPdfAnswer>(`${documentPath(id)}/ask`, { question });

export const getPersonalPdf = (id: string) => apiGetBlob(`${documentPath(id)}/content`);

export const deletePersonalPdf = (id: string) => apiDelete<{ deleted: boolean }>(documentPath(id));
