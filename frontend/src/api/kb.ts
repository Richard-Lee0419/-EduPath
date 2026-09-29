import { apiGet, apiPost, apiUpload } from "./http";

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
};

export type KnowledgeSearchResult = {
  course_id: number;
  query: string;
  top_k: number;
  corpus: KnowledgeCorpusSummary;
  results: KnowledgeSearchItem[];
};

export function uploadKnowledgeDocument(courseId: number, file: File) {
  const formData = new FormData();
  formData.append("courseId", String(courseId));
  formData.append("file", file);
  return apiUpload<{ document_id: number; parse_status: string }>("/kb/upload", formData);
}

export function listKnowledgeDocuments(params: { courseId?: number; status?: string } = {}) {
  return apiGet<
    Array<{
      id: number;
      document_id?: number;
      course_id: number;
      filename: string;
      parse_status: string;
      index_status: string;
      storage_status?: string;
      created_at?: string;
    }>
  >("/kb/documents", {
    courseId: params.courseId,
    status: params.status,
  });
}

export function searchKnowledgeBase(courseId: number, query: string, topK = 5) {
  return apiPost<KnowledgeSearchResult>("/kb/search", {
    course_id: courseId,
    query,
    top_k: topK,
  });
}
