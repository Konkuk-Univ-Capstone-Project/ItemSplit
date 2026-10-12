import type { ReceiptOcrJob } from './types';
export type OcrPollingOptions = {
  read: (signal: AbortSignal) => Promise<ReceiptOcrJob | null>;
  onResult: (job: ReceiptOcrJob | null) => void;
  onWait: (message: string) => void;
  onError: (error: unknown) => void;
};

import { ApiError } from './api';

/** Sequential reads only; callers dispose on receipt change or unmount. Never starts recognition. */
export function watchOcrStatus({ read, onResult, onWait, onError }: OcrPollingOptions): () => void {
  const controller = new AbortController();
  const startedAt = Date.now();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let transientFailures = 0;
  let rateLimits = 0;
  const schedule = (delay: number) => { timer = setTimeout(() => void run(), delay); };

  async function run() {
    try {
      const job = await read(controller.signal);
      if (controller.signal.aborted) return;
      transientFailures = 0; rateLimits = 0;
      onWait(''); onResult(job);
      if (job && ['PENDING', 'PROCESSING'].includes(job.status)) {
        schedule(Date.now() - startedAt < 10000 ? 2000 : 5000);
      }
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 429) {
        const fallback = Math.min(30000, 5000 * 2 ** Math.min(rateLimits++, 3));
        const delay = Math.max(1000, error.retryAfterMs ?? fallback);
        onWait(`조회 요청이 많아 ${Math.ceil(delay / 1000)}초 후 상태를 다시 확인합니다.`);
        schedule(delay);
      } else if ((error instanceof TypeError || (error instanceof ApiError && error.status >= 500))
        && ++transientFailures <= 3) {
        const delay = 5000 * 2 ** (transientFailures - 1);
        onWait(`연결이 원활하지 않아 ${delay / 1000}초 후 상태를 다시 확인합니다. (${transientFailures}/3)`);
        schedule(delay);
      } else {
        onWait(''); onError(error);
      }
    }
  }
  void run();
  return () => { controller.abort(); clearTimeout(timer); };
}
