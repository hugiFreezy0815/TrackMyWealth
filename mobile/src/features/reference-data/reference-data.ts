import type { components } from '@/api/schema';
import { typedApi } from '@/api/typed-api';

/** US-01-05: read-only contract of GET /api/v1/admin/reference-data (US-01-04). */
export type ReferenceDataResponse = components['schemas']['ReferenceDataResponse'];

export function loadReferenceData(): Promise<ReferenceDataResponse> {
  return typedApi.get('/api/v1/admin/reference-data');
}
