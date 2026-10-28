import { describe, expectTypeOf, it } from 'vitest'

import type {
  AdminJobDto,
  ImportMetadata,
  ModelConfigurationRequest,
  SourceRequest,
} from './adminTypes'
import type { ReportUgcRequest } from './types'

describe('shared API types', () => {
  it('mirrors the UGC report request contract', () => {
    expectTypeOf<ReportUgcRequest>().toEqualTypeOf<{ reason: string }>()
  })

  it('models source, import, job, and model configuration contracts explicitly', () => {
    expectTypeOf<SourceRequest>().toMatchTypeOf<{
      name: string
      connectorType: string
      scheduleType: string
    }>()
    expectTypeOf<ImportMetadata>().toMatchTypeOf<{
      items: Array<{ clientId: string; displayTitle: string }>
    }>()
    expectTypeOf<AdminJobDto>().toMatchTypeOf<{
      id: number
      status: string
      items: unknown[]
    }>()
    expectTypeOf<ModelConfigurationRequest>().toMatchTypeOf<{
      kind: string
      provider: string
      model: string
    }>()
  })
})
