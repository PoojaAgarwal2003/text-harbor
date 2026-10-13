import { expect, test } from '@playwright/test'
import path from 'node:path'

test('extracts, persists, downloads and deletes a real text document', async ({ page }) => {
  const externalRequests: string[] = []
  page.on('request', request => {
    if (!new URL(request.url()).hostname.match(/^(127\.0\.0\.1|localhost)$/)) externalRequests.push(request.url())
  })
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Browse files' })).toBeEnabled()
  await page.getByLabel('Choose files to extract').setInputFiles({
    name: 'harbor-notes.txt', mimeType: 'text/plain',
    buffer: Buffer.from('Text Harbor integration\nLocal extraction. No paid API.\nनमस्ते भारत'),
  })
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/Text Harbor integration/)
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/नमस्ते भारत/)
  await page.reload()
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/No paid API/)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download TXT' }).click()
  expect((await download).suggestedFilename()).toBe('harbor-notes.txt.txt')
  await page.getByRole('tab', { name: 'Details' }).click()
  await expect(page.getByText('text/plain', { exact: true })).toBeVisible()
  const jsonDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download JSON' }).click()
  expect((await jsonDownload).suggestedFilename()).toBe('harbor-notes.txt.json')
  await page.getByRole('button', { name: 'Delete harbor-notes.txt' }).click()
  await expect(page.getByRole('status')).toContainText('Job and stored result removed')
  expect(externalRequests).toEqual([])
})

test('extracts a scanned image through the actual Tesseract worker', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Browse files' })).toBeEnabled()
  await page.getByLabel('Choose files to extract').setInputFiles(path.resolve('../backend/target/smoke-fixtures/scan.png'))
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/TEXT HARBOR/)
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/LOCAL OCR/)
  await page.getByRole('button', { name: 'Delete scan.png' }).click()
})

test('extracts both native and scanned pages from a mixed PDF', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Browse files' })).toBeEnabled()
  await page.getByLabel('Choose files to extract').setInputFiles(path.resolve('../backend/target/smoke-fixtures/mixed.pdf'))
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/Native page from Text Harbor/)
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveValue(/TEXT HARBOR/)
  await page.getByRole('button', { name: 'Delete mixed.pdf' }).click()
})

test('reports unsupported binary content without displaying a fake result', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Browse files' })).toBeEnabled()
  await page.getByLabel('Choose files to extract').setInputFiles({
    name: 'unknown.bin', mimeType: 'application/octet-stream',
    buffer: Buffer.from([0, 1, 0, 2, 0, 3, 0, 255, 0, 255]),
  })
  await expect(page.getByText('UNSUPPORTED_FORMAT', { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Extracted text', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Delete unknown.bin' }).click()
})

test('works at mobile widths and explains privacy with keyboard-accessible dialog', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Browse files' })).toBeEnabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: 'Quick guide' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
})
