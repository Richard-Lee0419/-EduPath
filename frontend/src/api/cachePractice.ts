import { apiGet, apiPost } from './http';
import type { SpecialtyPracticeState } from './specialtyPractice';

export interface CacheBits {
  tag: number;
  index: number;
  offset: number;
}

export interface CacheExercise {
  id: string;
  address_bits: number;
  capacity_bytes: number;
  block_bytes: number;
  ways: number;
}

export const getCachePractice = () =>
  apiGet<SpecialtyPracticeState<CacheExercise, CacheBits>>('/evaluation/cache-practice');

export const submitCachePractice = (exerciseId: string, answer: CacheBits) =>
  apiPost<SpecialtyPracticeState<CacheExercise, CacheBits>>('/evaluation/cache-practice/attempts', {
    exercise_id: exerciseId, ...answer,
  });
