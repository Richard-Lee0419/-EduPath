import { apiGet, apiPost } from "./http";
import type { TaskCreated } from "@/types/api";

export function generateLearningPath(courseIds: number[]) {
  return apiPost<TaskCreated>("/path/generate", { course_ids: courseIds });
}

export function getCurrentLearningPath() {
  return apiGet<Record<string, unknown>>("/path/current");
}
