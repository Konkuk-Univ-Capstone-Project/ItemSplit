import type { ReceiptOcrJob } from './types';

export function imageRetryAvailable(job: ReceiptOcrJob, imageId: number, canRetry: boolean, dirty: boolean): boolean {
  const image = job.images.find(image => image.imageId === imageId);
  return canRetry && !dirty && job.draft.status === 'EDITING'
    && !['PENDING', 'PROCESSING'].includes(job.status)
    && image?.status === 'FAILED' && image.retryable && image.attemptCount < 3;
}
