import { apiGet, apiPost } from './http';
import type { SpecialtyPracticeState } from './specialtyPractice';

export interface TreeExercise {
  id: string;
  order: 'preorder' | 'inorder' | 'postorder';
  nodes: Array<{ id: string; left?: string; right?: string }>;
}

export const getTreePractice = () =>
  apiGet<SpecialtyPracticeState<TreeExercise, string[]>>('/evaluation/tree-practice');

export const submitTreePractice = (exerciseId: string, sequence: string[]) =>
  apiPost<SpecialtyPracticeState<TreeExercise, string[]>>('/evaluation/tree-practice/attempts', {
    exercise_id: exerciseId, sequence,
  });
