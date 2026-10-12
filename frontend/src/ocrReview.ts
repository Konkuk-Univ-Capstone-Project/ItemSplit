import type { OcrDraftWrite, ReceiptOcrJob } from './types';

/** Recompute from the current quantity; never adopt a stale server candidate after an edit. */
export function calculatedUnitPrice(line: OcrDraftWrite['items'][number], original?: ReceiptOcrJob['draft']['items'][number]): number | null {
  const amount = original?.ocr.lineAmount;
  const quantity = line.quantity;
  if (!original || original.ocr.unitPrice != null || line.price != null || line.removed
    || amount == null || !Number.isSafeInteger(amount) || amount <= 0 || amount > 10_000_000
    || quantity == null || !Number.isSafeInteger(quantity) || quantity < 1 || quantity > 999
    || amount % quantity !== 0) return null;
  const candidate = amount / quantity;
  return candidate <= 1_000_000 ? candidate : null;
}

export function imageRetryAvailable(job: ReceiptOcrJob, imageId: number, canRetry: boolean, dirty: boolean): boolean {
  const image = job.images.find(image => image.imageId === imageId);
  return canRetry && !dirty && job.draft.status === 'EDITING'
    && !['PENDING', 'PROCESSING'].includes(job.status)
    && image?.status === 'FAILED' && image.retryable && image.attemptCount < 3;
}
