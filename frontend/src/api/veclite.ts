import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type {
  VectorStoreDefinition,
  VectorStoreStats,
  VectorDocument,
  VectorSearchRequest,
  VectorSearchResult,
  DeleteResult,
  FilterExpression
} from '../types'

class VecLiteApiClient {
  private client: AxiosInstance
  private baseUrl: string

  constructor() {
    this.baseUrl = localStorage.getItem('veclite_base_url') || '/veclite/api/v1'
    this.client = axios.create({
      baseURL: this.baseUrl,
      timeout: 15000,
      headers: {
        'Content-Type': 'application/json'
      }
    })
  }

  public getBaseUrl(): string {
    return this.baseUrl
  }

  public setBaseUrl(url: string) {
    let cleanUrl = url.trim()
    if (cleanUrl.endsWith('/')) {
      cleanUrl = cleanUrl.slice(0, -1)
    }
    this.baseUrl = cleanUrl
    localStorage.setItem('veclite_base_url', cleanUrl)
    this.client.defaults.baseURL = cleanUrl
  }

  // Health check / ping
  async checkHealth(): Promise<{ ok: boolean; latencyMs: number; error?: string }> {
    const start = performance.now()
    try {
      await this.client.get('/stores')
      const latencyMs = Math.round(performance.now() - start)
      return { ok: true, latencyMs }
    } catch (err: any) {
      const latencyMs = Math.round(performance.now() - start)
      return { ok: false, latencyMs, error: err.message || 'Connection failed' }
    }
  }

  // Stores
  async listStores(): Promise<string[]> {
    const res = await this.client.get<string[]>('/stores')
    return res.data || []
  }

  async createStore(storeName: string, definition: VectorStoreDefinition): Promise<string> {
    const res = await this.client.post<string>(`/stores/${encodeURIComponent(storeName)}`, definition)
    return res.data
  }

  async dropStore(storeName: string): Promise<string> {
    const res = await this.client.delete<string>(`/stores/${encodeURIComponent(storeName)}`)
    return res.data
  }

  async getStats(storeName: string): Promise<VectorStoreStats> {
    const res = await this.client.get<VectorStoreStats>(`/stores/${encodeURIComponent(storeName)}/stats`)
    return res.data
  }

  // Documents
  async listDocuments(
    storeName: string,
    page: number = 1,
    size: number = 20,
    includeVector: boolean = true
  ): Promise<VectorDocument[]> {
    const res = await this.client.get<VectorDocument[]>(`/stores/${encodeURIComponent(storeName)}/documents`, {
      params: { page, size, includeVector }
    })
    return res.data || []
  }

  async upsertDocument(storeName: string, document: VectorDocument): Promise<string> {
    const res = await this.client.post<string>(`/stores/${encodeURIComponent(storeName)}/documents`, document)
    return res.data
  }

  async upsertBatch(storeName: string, documents: VectorDocument[]): Promise<string> {
    const res = await this.client.post<string>(`/stores/${encodeURIComponent(storeName)}/documents/batch`, documents)
    return res.data
  }

  async deleteByIds(storeName: string, ids: string[]): Promise<DeleteResult> {
    const res = await this.client.delete<DeleteResult>(`/stores/${encodeURIComponent(storeName)}/documents`, {
      data: ids
    })
    return res.data
  }

  async deleteByFilter(storeName: string, filter: FilterExpression): Promise<DeleteResult> {
    const res = await this.client.delete<DeleteResult>(`/stores/${encodeURIComponent(storeName)}/documents/filter`, {
      data: filter
    })
    return res.data
  }

  // Search & Debug
  async searchByVector(storeName: string, request: VectorSearchRequest): Promise<VectorSearchResult[]> {
    const res = await this.client.post<VectorSearchResult[]>(
      `/stores/${encodeURIComponent(storeName)}/search/vector`,
      request
    )
    return res.data || []
  }

  async searchByText(storeName: string, request: VectorSearchRequest): Promise<VectorSearchResult[]> {
    const res = await this.client.post<VectorSearchResult[]>(
      `/stores/${encodeURIComponent(storeName)}/search/text`,
      request
    )
    return res.data || []
  }

  async hybridSearch(storeName: string, request: VectorSearchRequest): Promise<VectorSearchResult[]> {
    const res = await this.client.post<VectorSearchResult[]>(
      `/stores/${encodeURIComponent(storeName)}/search/hybrid`,
      request
    )
    return res.data || []
  }

  // Persistence
  async refreshSnapshot(storeName: string): Promise<string> {
    const res = await this.client.post<string>(`/stores/${encodeURIComponent(storeName)}/refresh`)
    return res.data
  }

  async reloadStore(storeName: string): Promise<string> {
    const res = await this.client.post<string>(`/stores/${encodeURIComponent(storeName)}/reload`)
    return res.data
  }
}

export const api = new VecLiteApiClient()
export default api
