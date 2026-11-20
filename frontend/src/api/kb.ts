import { apiDelete, apiGet, apiGetBlob, apiPost, apiUpload } from "./http";
import type { TaskCreated } from '@/types/api';

export type KnowledgeCorpusSummary = {
  mode: 'course_corpus' | 'builtin_demo' | 'unknown';
  documents: number;
  external_documents: number;
  chunks: number;
  vector_store: string;
  embedding: string;
};

export type KnowledgeSearchItem = {
  chunk_id: string;
  course_id: number;
  knowledge_point_id: number;
  knowledge_point: string;
  related_knowledge_point_ids: number[];
  title: string;
  content: string;
  score: number;
  source: string;
  author: string;
  source_url: string;
  license: string;
  license_status: string;
  document_type: string;
  contains_examples: boolean;
  heading_path: string[];
  document_id?: number;
  page?: number;
};

export type KnowledgeSearchResult = {
  course_id: number;
  query: string;
  top_k: number;
  corpus: KnowledgeCorpusSummary;
  results: KnowledgeSearchItem[];
};

export type KnowledgeDocumentUploadResult = {
  document_id: number;
  parse_status: string;
  index_status: string;
  safety_status: string | null;
  index_error: string;
};

export type KnowledgeDocument = {
  id: number;
  document_id?: number;
  course_id: number;
  filename: string;
  parse_status: string;
  index_status: string;
  index_error?: string;
  knowledge_point_id?: number;
  storage_status?: string;
  created_at?: string;
};

export function uploadKnowledgeDocument(courseId: number, file: File, knowledgePointId?: number) {
  const formData = new FormData();
  formData.append("courseId", String(courseId));
  formData.append("file", file);
  const path = knowledgePointId ? `/kb/upload?knowledgePointId=${knowledgePointId}` : '/kb/upload';
  return apiUpload<KnowledgeDocumentUploadResult>(path, formData);
}

export async function listKnowledgeDocuments(params: { courseId?: number; status?: string } = {}) {
  const rows = await apiGet<KnowledgeDocument[]>("/kb/documents", {
    courseId: params.courseId,
    status: params.status,
  });
  return rows.map((row) => ({ ...row, id: row.document_id ?? row.id }));
}

export const getCoursePdf = (id: number) => apiGetBlob(`/kb/documents/${id}/content`);

export const reindexKnowledgeDocument = (id: number) => apiPost<TaskCreated>(`/kb/documents/${id}/reindex`);

export const deleteKnowledgeDocument = (id: number) => apiDelete(`/kb/documents/${id}`);

export function searchKnowledgeBase(courseId: number, query: string, topK = 5) {
  return apiPost<KnowledgeSearchResult>("/kb/search", {
    course_id: courseId,
    query,
    top_k: topK,
  });
}
