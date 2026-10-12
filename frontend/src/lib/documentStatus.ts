import type { DocumentStatus } from '../api'

const LABELS: Record<DocumentStatus, string> = {
  UPLOADING: 'Uploading',
  PROCESSING: 'Processing',
  EXTRACTING: 'Extracting text',
  CHUNKING: 'Chunking',
  EMBEDDING: 'Embedding',
  INDEXING: 'Indexing',
  READY: 'Ready',
  FAILED: 'Failed',
  DELETED: 'Deleted',
}

/** Progress step 0..6 used by the stepper; -1 for terminal non-ready states. */
const STEPS: Record<DocumentStatus, number> = {
  UPLOADING: 0,
  PROCESSING: 1,
  EXTRACTING: 2,
  CHUNKING: 3,
  EMBEDDING: 4,
  INDEXING: 5,
  READY: 6,
  FAILED: -1,
  DELETED: -1,
}

export const statusLabel = (status: DocumentStatus): string => LABELS[status] ?? status

export const statusStep = (status: DocumentStatus): number => STEPS[status] ?? -1

/** Still inside the ingestion pipeline (not READY / FAILED / DELETED). */
export const isProcessing = (status: DocumentStatus): boolean => {
  const step = statusStep(status)
  return step >= 0 && status !== 'READY'
}

export const isReady = (status: DocumentStatus): boolean => status === 'READY'

export const isFailed = (status: DocumentStatus): boolean => status === 'FAILED'
