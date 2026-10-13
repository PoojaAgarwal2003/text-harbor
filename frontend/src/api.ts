export type Status = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
export type OcrMode = 'AUTO' | 'ALWAYS' | 'NEVER'

export interface Job {
  id: string
  filename: string
  size: number
  status: Status
  createdAt: string
  updatedAt: string
  language: string
  ocrMode: OcrMode
  mediaType?: string
  characters: number
  warnings: string[]
  errorCode?: string
  errorMessage?: string
}

export interface Entry { job: Job; accessToken: string }
export interface Result { text: string; mediaType: string; warnings: string[] }
export interface Capabilities {
  maxUploadBytes: number
  maxPages: number
  maxCharacters: number
  retentionMinutes: number
  ocrAvailable: boolean
  languages: string[]
  formats: string[]
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message) }
}

export const terminal = (status: Status) => !['QUEUED', 'PROCESSING'].includes(status)

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, options)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError('Cannot reach the local server. Check that the backend is running.', 0)
  }
  if (response.status === 204) return undefined as T
  if (!response.headers.get('content-type')?.includes('application/json')) {
    throw new ApiError(`The server returned an unexpected response (${response.status}).`, response.status)
  }
  const body: unknown = await response.json()
  if (!response.ok) {
    const message = body && typeof body === 'object' && 'message' in body && typeof body.message === 'string'
      ? body.message : `Request failed (${response.status}).`
    throw new ApiError(message, response.status)
  }
  return body as T
}

export const jobHeaders = (entry: Entry) => ({ 'X-Job-Token': entry.accessToken })
export const errorMessage = (error: unknown) => error instanceof Error ? error.message : 'An unexpected error occurred.'
export const aborted = (error: unknown) => error instanceof DOMException && error.name === 'AbortError'

export function download(text: string, filename: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function formatBytes(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
