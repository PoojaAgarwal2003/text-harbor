import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { vi, test, expect } from 'vitest'
import App from './App'

const capabilities = {
  maxUploadBytes: 1024, maxPages: 200, maxCharacters: 1_000_000,
  retentionMinutes: 60, ocrAvailable: true, languages: ['eng', 'hin'], formats: ['PDF', 'DOCX'],
}
const respond = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

test('uploads a file and displays returned text without interpreting it as HTML', async () => {
  const job = {
    id: 'test-id', filename: 'notes.txt', size: 5, status: 'COMPLETED', createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(), language: 'eng', ocrMode: 'AUTO', characters: 30, warnings: [],
  }
  const fetchMock = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.endsWith('/capabilities')) return respond(capabilities)
    if (url.endsWith('/result')) {
      expect(options?.headers).toEqual({ 'X-Job-Token': 'private-token' })
      return respond({ text: '<script>alert("not executable")</script>', mediaType: 'text/plain', warnings: [] })
    }
    if (url.endsWith('/jobs') && options?.method === 'POST') {
      expect((options.body as FormData).get('ocrMode')).toBe('AUTO')
      return respond({ job, accessToken: 'private-token' }, 202)
    }
    throw new Error(`Unexpected request: ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Browse files' })).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Choose files to extract'), {
    target: { files: [new File(['Hello'], 'notes.txt', { type: 'text/plain' })] },
  })
  expect(await screen.findByLabelText('Extracted text')).toHaveValue('<script>alert("not executable")</script>')
  expect(document.querySelector('script')).toBeNull()
  expect(screen.getByRole('button', { name: 'Download TXT' })).toBeEnabled()
})

test('rejects oversized uploads before contacting the extraction endpoint', async () => {
  const fetchMock = vi.fn(async () => respond(capabilities))
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Browse files' })).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Choose files to extract'), {
    target: { files: [new File(['x'.repeat(1025)], 'large.txt')] },
  })
  expect(await screen.findByRole('status')).toHaveTextContent('choose a non-empty file')
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

test('shows connection failures and allows retry without fabricating a working engine', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
  render(<App />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the local server')
  expect(screen.getByRole('button', { name: 'Browse files' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Retry connection' })).toBeEnabled()
})

test('shows server validation errors and does not create a false completed job', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/capabilities')
    ? respond(capabilities) : respond({ message: 'Workers are busy. Retry shortly.' }, 429)))
  render(<App />)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Browse files' })).toBeEnabled())
  fireEvent.change(screen.getByLabelText('Choose files to extract'), {
    target: { files: [new File(['Hello'], 'notes.txt')] },
  })
  expect(await screen.findByRole('status')).toHaveTextContent('Workers are busy')
  expect(screen.queryByLabelText('Extracted text')).not.toBeInTheDocument()
})
