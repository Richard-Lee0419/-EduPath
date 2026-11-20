interface PracticeAttempt<Exercise, Answer> {
  exercise: Exercise;
  answer: Answer;
  feedback: {
    correct: boolean;
    expected: Answer;
    mistake: string;
    explanation: string;
    provenance?: { source: string; request_id: string; execution_id: string };
    citation: {
      document: string;
      section: string;
      source_version: string;
      excerpt: string;
    };
  };
  submitted_at: string;
}

export interface SpecialtyPracticeState<Exercise, Answer> {
  stage: 'initial' | 'retest' | 'complete';
  next_exercise?: Exercise | null;
  initial?: PracticeAttempt<Exercise, Answer> | null;
  retest?: PracticeAttempt<Exercise, Answer> | null;
  focus?: string | null;
}
