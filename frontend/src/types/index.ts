export type MetricType = 'COSINE' | 'L2' | 'DOT_PRODUCT' | 'IP'
export type QuantizationType = 'NONE' | 'SQ8' | 'FP16'
export type SearchMode = 'TEXT' | 'VECTOR' | 'HYBRID'

export interface VectorStoreDefinition {
  storeName: string
  dimension: number
  metric?: MetricType | string
  maxCapacity?: number
  embeddingModel?: string
  quantization?: QuantizationType | string
  indexedMetadataFields?: string[]
}

export interface VectorStoreStats {
  storeName: string
  dimension: number
  docCount: number
  maxCapacity: number
  metric: string
  quantization: string
  memoryUsageBytes?: number
  activeCount?: number
  deletedCount?: number
}

export interface VectorDocument {
  id: string
  text?: string
  vector?: number[]
  metadata?: Record<string, any>
}

export interface FilterExpression {
  field: string
  operator: 'EQ' | 'IN' | 'RANGE' | 'PREFIX' | 'GT' | 'LT'
  value?: any
  values?: any[]
  from?: any
  to?: any
  includeFrom?: boolean
  includeTo?: boolean
  prefix?: string
}

export interface VectorSearchRequest {
  storeName?: string
  mode?: SearchMode
  queryText?: string
  queryVector?: number[]
  topK?: number
  threshold?: number
  filter?: FilterExpression
  includeVector?: boolean
}

export interface VectorSearchResult {
  id: string
  score: number
  document?: VectorDocument
  metadata?: Record<string, any>
  text?: string
  vector?: number[]
}

export interface DeleteResult {
  deletedCount: number
  success?: boolean
  message?: string
}

export interface ApiResponse<T = any> {
  code?: number
  message?: string
  data?: T
}
