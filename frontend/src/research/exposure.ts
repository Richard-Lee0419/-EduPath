/**
 * 曝光埋点（W01）。
 *
 * 关键口径：**页面加载 ≠ 用户看到**。`reason_exposed` / `evidence_exposed`
 * 必须在对应元素真正进入可视区域之后才记录，因此这里使用 IntersectionObserver，
 * 并且对同一个 exposure key 只记录一次（重复曝光有明确去重规则）。
 *
 * 无 IntersectionObserver 的环境（例如服务端渲染 / 老浏览器）不会伪造曝光：
 * 直接不记录，避免把"没看到"写成"看到了"。
 */

import { shouldEmitExposure, type ResearchEventInput, type ResearchEventName } from './core';
import { track } from './client';

export interface ExposureOptions {
  /** 视为"已进入可视区域"的阈值，默认 0.5（至少一半可见）。 */
  threshold?: number;
}

/**
 * 观察一个元素，首次真正进入可视区域时记录一次曝光事件。
 *
 * @returns 清理函数（组件卸载时调用，避免观察器泄漏）
 */
export function observeExposure(
  element: Element | null | undefined,
  exposureKey: string,
  eventName: ResearchEventName,
  buildPayload: () => Omit<ResearchEventInput, 'eventName'> = () => ({}),
  options: ExposureOptions = {},
): () => void {
  if (!element) return () => {};
  if (typeof IntersectionObserver === 'undefined') return () => {};

  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        observer.disconnect();
        if (!shouldEmitExposure(exposureKey)) return;
        const payload = buildPayload();
        track(eventName, { ...payload, metadata: { ...payload.metadata, exposure_key: exposureKey } });
      }
    },
    { threshold: options.threshold ?? 0.5 },
  );
  observer.observe(element);
  return () => observer.disconnect();
}

export { shouldEmitExposure, resetExposureCache } from './core';
