import sampleUrl from '@/assets/cache-review-sample.pdf?url';

export const reviewSampleName = 'Cache 地址拆分 · 示例讲义.pdf';
export const reviewSampleQuestion = '这道四路组相联例题中的组索引为什么是 6 位？';

export async function loadReviewSample() {
  const response = await fetch(sampleUrl);
  if (!response.ok) throw new Error('示例讲义加载失败，请重试或上传自己的 PDF。');
  return new File([await response.blob()], reviewSampleName, { type: 'application/pdf' });
}
