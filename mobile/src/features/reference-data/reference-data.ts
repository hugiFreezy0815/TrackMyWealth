import { api } from '@/api/client';

/** US-01-05: read-only contract of GET /api/v1/admin/reference-data (US-01-04). */
export type ReferenceDataResponse = {
  packageVersion: string;
  publicationDate: string;
  importedAt: string;
  /** Null for the reference baseline shipped with the application. */
  importedBy: string | null;
  contents: {
    catalogueInstitutions: number;
    defaultCategories: number;
    sourceCodeMappings: number;
    fallbackSectors: number;
    gicsStructureVersion: string | null;
  };
  stalenessWarnings: {
    code: string;
    subject: string;
    missingPeriod: string;
  }[];
};

export function loadReferenceData(): Promise<ReferenceDataResponse> {
  return api.get<ReferenceDataResponse>('/api/v1/admin/reference-data');
}
