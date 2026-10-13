import { useEffect, useRef, useState } from 'react'
import {
  ArrowDownToLine, ArrowRight, Check, ChevronRight, CircleHelp, Clipboard,
  File, FileCheck2, FileText, FolderOpen, HardDrive, Layers3, LoaderCircle,
  LockKeyhole, ScanText, ShieldCheck, Sparkles, Trash2, Upload, X,
} from 'lucide-react'
import {
  ApiError, aborted, download, errorMessage, formatBytes, jobHeaders, request, terminal,
  type Capabilities, type Entry, type Job, type OcrMode, type Result,
} from './api'

const languageNames: Record<string, string> = {
  eng: 'English', hin: 'Hindi', 'eng+hin': 'English + Hindi', fra: 'French', deu: 'German', spa: 'Spanish',
}
const statusNames: Record<Job['status'], string> = {
  QUEUED: 'Queued', PROCESSING: 'Extracting', COMPLETED: 'Ready', PARTIAL: 'Review needed',
  FAILED: 'Failed', CANCELLED: 'Cancelled',
}
const historyKey = 'text-harbor-jobs-v1'

export default function App() {
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null)
  const [entries, setEntries] = useState<Entry[]>([])
  const [restored, setRestored] = useState(false)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [loaded, setLoaded] = useState<{ id: string; result: Result } | null>(null)
  const [loadingResult, setLoadingResult] = useState(false)
  const [resultError, setResultError] = useState('')
  const [notice, setNotice] = useState('')
  const [connectionError, setConnectionError] = useState('')
  const [uploading, setUploading] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [language, setLanguage] = useState('eng')
  const [ocrMode, setOcrMode] = useState<OcrMode>('AUTO')
  const [tab, setTab] = useState<'text' | 'details'>('text')
  const [modal, setModal] = useState<'formats' | 'privacy' | null>(null)
  const [copied, setCopied] = useState(false)
  const [retry, setRetry] = useState(0)
  const input = useRef<HTMLInputElement>(null)
  const dialog = useRef<HTMLDialogElement>(null)
  const uploadLock = useRef(false)
  const active = entries.find(entry => entry.job.id === activeId)
  const result = loaded?.id === activeId ? loaded.result : null
  const ready = active?.job.status === 'COMPLETED' || active?.job.status === 'PARTIAL'

  useEffect(() => {
    const controller = new AbortController()
    request<Capabilities>('/capabilities', { signal: controller.signal })
      .then(value => {
        setCapabilities(value)
        setConnectionError('')
        setLanguage(current => value.languages.includes(current) ? current : value.languages[0] ?? 'eng')
      })
      .catch(error => { if (!aborted(error)) setConnectionError(errorMessage(error)) })
    return () => controller.abort()
  }, [retry])

  useEffect(() => {
    try {
      const stored: unknown = JSON.parse(sessionStorage.getItem(historyKey) ?? '[]')
      if (!Array.isArray(stored) || !stored.every(isEntry)) throw new Error('Invalid session history')
      setEntries(stored)
      setActiveId(stored[0]?.job.id ?? null)
    } catch {
      setNotice('Session history could not be restored. New uploads still work.')
    }
    setRestored(true)
  }, [])

  useEffect(() => {
    if (!restored) return
    try {
      sessionStorage.setItem(historyKey, JSON.stringify(entries))
    } catch {
      setNotice('Session storage is unavailable. Job history will not survive a page reload.')
    }
  }, [entries, restored])

  useEffect(() => {
    const pending = entries.filter(entry => !terminal(entry.job.status))
    if (!pending.length) return
    const controller = new AbortController()
    const timer = setTimeout(async () => {
      let failedConnection = false
      const updates = await Promise.all(pending.map(async entry => {
        try {
          const job = await request<Job>(`/jobs/${entry.job.id}`, {
            headers: jobHeaders(entry), signal: controller.signal,
          })
          return { ...entry, job }
        } catch (error) {
          if (aborted(error)) return entry
          if (error instanceof ApiError && error.status === 404) {
            return { ...entry, job: { ...entry.job, status: 'FAILED' as const, errorMessage: 'This job expired or was removed. Upload it again.' } }
          } else {
            setConnectionError(errorMessage(error))
            failedConnection = true
          }
          return entry
        }
      }))
      if (!controller.signal.aborted) {
        setEntries(current => current.map(entry => terminal(entry.job.status)
          ? entry : updates.find(update => update.job.id === entry.job.id) ?? entry))
        if (!failedConnection) setConnectionError('')
      }
    }, 1500)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [entries, retry])

  useEffect(() => {
    setResultError('')
    setCopied(false)
    if (!active || !ready) return
    const controller = new AbortController()
    setLoadingResult(true)
    request<Result>(`/jobs/${active.job.id}/result`, { headers: jobHeaders(active), signal: controller.signal })
      .then(value => setLoaded({ id: active.job.id, result: value }))
      .catch(error => { if (!aborted(error)) setResultError(errorMessage(error)) })
      .finally(() => { if (!controller.signal.aborted) setLoadingResult(false) })
    return () => controller.abort()
  }, [activeId, ready, retry]) // A completed job's result is immutable.

  useEffect(() => {
    if (modal) dialog.current?.showModal()
    else dialog.current?.close()
  }, [modal])

  async function upload(files: FileList | File[]) {
    if (!capabilities || uploadLock.current) return
    const batch = Array.from(files)
    if (batch.length > 8) { setNotice('Select up to 8 files per batch.'); return }
    uploadLock.current = true
    setUploading(true)
    setNotice('')
    const failures: string[] = []
    for (const file of batch) {
      if (!file.size || file.size > capabilities.maxUploadBytes) {
        failures.push(`${file.name}: choose a non-empty file under ${formatBytes(capabilities.maxUploadBytes)}.`)
        continue
      }
      const body = new FormData()
      body.append('file', file)
      body.append('language', language)
      body.append('ocrMode', ocrMode)
      try {
        const entry = await request<Entry>('/jobs', { method: 'POST', body })
        setEntries(current => [entry, ...current])
        setActiveId(entry.job.id)
        setTab('text')
        setConnectionError('')
      } catch (error) {
        failures.push(`${file.name}: ${errorMessage(error)}`)
      }
    }
    if (failures.length) setNotice(failures.join(' | '))
    setUploading(false)
    uploadLock.current = false
    if (input.current) input.current.value = ''
  }

  async function cancel(entry: Entry) {
    try {
      const job = await request<Job>(`/jobs/${entry.job.id}/cancel`, { method: 'POST', headers: jobHeaders(entry) })
      setEntries(current => current.map(item => item.job.id === job.id ? { ...item, job } : item))
    } catch (error) { setNotice(errorMessage(error)) }
  }

  async function remove(entry: Entry) {
    try {
      await request<void>(`/jobs/${entry.job.id}`, { method: 'DELETE', headers: jobHeaders(entry) })
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) {
        setNotice(errorMessage(error))
        return
      }
    }
    setEntries(current => current.filter(item => item.job.id !== entry.job.id))
    if (activeId === entry.job.id) {
      setLoaded(null)
      setActiveId(null)
    }
    setNotice('Job and stored result removed.')
  }

  async function copy() {
    if (!result) return
    try {
      await navigator.clipboard.writeText(result.text)
      setCopied(true)
    } catch { setNotice('Clipboard permission was denied. Select the text or download the TXT file.') }
  }

  const languageOptions = capabilities?.languages ?? []
  const completeCount = entries.filter(entry => ['COMPLETED', 'PARTIAL'].includes(entry.job.status)).length
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="./" aria-label="Text Harbor home"><span className="brand-icon">H<span /></span><span>text harbor<span className="brand-period">.</span></span></a>
        <div className="workspace-badge"><span className="workspace-avatar"><HardDrive size={17} /></span><div>Personal workspace<small>LOCAL EDITION</small></div><LockKeyhole size={13} /></div>
        <p className="nav-label">WORKSPACE</p>
        <nav aria-label="Workspace navigation">
          <button className="nav-item selected" onClick={() => input.current?.focus()}><ScanText size={19} />Text extraction<span className="nav-dot" /></button>
          <button className="nav-item" onClick={() => setModal('formats')}><Layers3 size={19} />Supported formats<ChevronRight size={14} /></button>
          <button className="nav-item" onClick={() => setModal('privacy')}><ShieldCheck size={19} />Privacy & storage<ChevronRight size={14} /></button>
        </nav>
        <div className="sidebar-bottom">
          <div className="privacy-card"><div className="privacy-symbol"><LockKeyhole size={19} /></div><strong>Your files. Your space.</strong><p>Local processing. No cloud OCR.<br />Not a single AI token.</p><button onClick={() => setModal('privacy')}>Built to stay private <ArrowRight size={14} /></button></div>
          <span className="version"><span className="small-dot" />OPEN SOURCE ENGINE<span>v1.0</span></span>
        </div>
      </aside>

      <main className="main">
        <header className="topbar"><span>Workspace <ChevronRight size={13} /><strong>Text extraction</strong></span><button className="help-button" onClick={() => setModal('formats')}><CircleHelp size={16} /><span>Quick guide</span></button></header>
        <div className="page-content">
          <div className="heading-row"><div><div className="eyebrow"><span />DOCUMENTS IN. POSSIBILITIES OUT.</div><h1>Your documents.<br /><em>Just the text.</em></h1><p className="intro">Turn documents, scans, and images into text you can actually use.<br className="desktop-break" /> Fast, local, and refreshingly uncomplicated.</p></div><div className="engine-badge"><span className={connectionError ? 'status-light offline' : capabilities ? 'status-light' : 'status-light waiting'} /><div>{connectionError ? 'Engine disconnected' : capabilities ? 'Local engine connected' : 'Connecting to engine'}<small>{capabilities?.ocrAvailable ? 'Tika + Tesseract OCR' : capabilities ? 'Native text extraction' : 'Checking local services'}</small></div></div></div>

          {connectionError && <div role="alert" className="banner error"><span>{connectionError}</span><button onClick={() => setRetry(value => value + 1)}>Retry connection</button></div>}
          {notice && <div role="status" className="banner"><span>{notice}</span><button aria-label="Dismiss notification" onClick={() => setNotice('')}><X size={16} /></button></div>}
          {capabilities && !capabilities.ocrAvailable && <div className="banner warning"><ShieldCheck size={18} /><span>Text extraction is ready. Install Tesseract to read scanned documents and images.</span></div>}

          <div className="workbench">
            <section className="upload-panel panel" aria-labelledby="upload-title">
              <div className="panel-heading"><div><span className="step">01</span><h2 id="upload-title">Add your files</h2></div><span className="subtle-label">START HERE</span></div>
              <div className={`dropzone ${dragging ? 'dragging' : ''} ${uploading ? 'uploading' : ''}`}
                onDragOver={event => { event.preventDefault(); setDragging(true) }}
                onDragLeave={() => setDragging(false)}
                onDrop={event => { event.preventDefault(); setDragging(false); void upload(event.dataTransfer.files) }}>
                <div className="upload-art"><div className="paper-back" /><div className="paper-front"><FileText size={32} strokeWidth={1.3} /></div><span><Upload size={17} /></span></div>
                <h3>{uploading ? 'Uploading to your workspace...' : 'Drop files, find the words'}</h3>
                <p>Drag & drop your documents here, or</p>
                <input ref={input} className="file-input" id="file-upload" type="file" multiple aria-label="Choose files to extract" disabled={!capabilities || uploading} onChange={event => { if (event.target.files) void upload(event.target.files) }} />
                <button className="primary-button" disabled={!capabilities || uploading} onClick={() => input.current?.click()}>{uploading ? <LoaderCircle className="spin" size={16} /> : <FolderOpen size={16} />}Browse files</button>
                <small>PDF, Word, images & more<span />{capabilities ? formatBytes(capabilities.maxUploadBytes) : '25 MB'} per file</small>
              </div>
              <div className="extraction-options">
                <div><label htmlFor="ocr-mode">Extraction mode</label><select id="ocr-mode" value={ocrMode} onChange={event => setOcrMode(event.target.value as OcrMode)}><option value="AUTO">Smart - native text first</option><option value="ALWAYS" disabled={!capabilities?.ocrAvailable}>Whole-page PDF OCR</option><option value="NEVER">Native text only</option></select></div>
                <div><label htmlFor="ocr-language">OCR language</label><select id="ocr-language" value={language} disabled={!capabilities?.ocrAvailable || ocrMode === 'NEVER'} onChange={event => setLanguage(event.target.value)}>{languageOptions.length ? languageOptions.map(code => <option key={code} value={code}>{languageNames[code] ?? code}</option>) : <option value="eng">English</option>}{languageOptions.includes('eng') && languageOptions.includes('hin') && <option value="eng+hin">English + Hindi</option>}</select></div>
              </div>
              <div className="queue-heading"><h3>Session files <span>{entries.length.toString().padStart(2, '0')}</span></h3><span>{completeCount} extracted</span></div>
              <div className="file-list">
                {entries.length === 0 ? <div className="empty-queue"><File size={18} /><span>A fresh start. Your files will appear here.</span></div> : entries.map(entry => <div className={`file-row ${activeId === entry.job.id ? 'active' : ''}`} key={entry.job.id}><button className="file-select" onClick={() => { setActiveId(entry.job.id); setTab('text') }}><span className={`file-type ${entry.job.filename.toLowerCase().endsWith('.pdf') ? 'pdf' : ''}`}><FileText size={20} /></span><span className="file-description"><strong>{entry.job.filename}</strong><small>{formatBytes(entry.job.size)}<span className={`job-status ${entry.job.status.toLowerCase()}`}>{!terminal(entry.job.status) && <LoaderCircle size={10} className="spin" />}{statusNames[entry.job.status]}</span></small></span></button>{terminal(entry.job.status) ? <button className="icon-button" aria-label={`Delete ${entry.job.filename}`} onClick={() => void remove(entry)}><Trash2 size={15} /></button> : <button className="icon-button" aria-label={`Cancel ${entry.job.filename}`} onClick={() => void cancel(entry)}><X size={16} /></button>}</div>)}
              </div>
              <div className="storage-note"><LockKeyhole size={12} />Results expire after {capabilities?.retentionMinutes ?? 60} minutes.</div>
            </section>

            <section className="result-panel panel" aria-labelledby="result-title">
              <div className="panel-heading"><div><span className="step">02</span><h2 id="result-title">Your extracted text</h2></div><span className="result-label"><span />{result ? 'READY TO USE' : 'OUTPUT'}</span></div>
              <div className="result-toolbar"><div role="tablist" aria-label="Result view"><button role="tab" aria-selected={tab === 'text'} onClick={() => setTab('text')}><FileText size={14} />Text</button><button role="tab" aria-selected={tab === 'details'} onClick={() => setTab('details')}>Details</button></div><div className="result-actions"><button aria-label="Copy extracted text" disabled={!result} onClick={() => void copy()}>{copied ? <Check size={15} /> : <Clipboard size={15} />}<span>{copied ? 'Copied' : 'Copy'}</span></button><button aria-label="Download TXT" disabled={!result || !active} onClick={() => { if (result && active) download(result.text, `${active.job.filename}.txt`, 'text/plain;charset=utf-8') }}><ArrowDownToLine size={15} /><span>TXT</span></button></div></div>
              <div className="result-body" role="tabpanel">
                {!active ? <div className="empty-result"><div className="result-illustration"><div className="illustration-orbit" /><div className="illustration-sheet"><ScanText size={30} strokeWidth={1.3} /><span /><span /><span /><span /></div><div className="illustration-check"><Check size={21} /></div><span className="sparkle-one">+</span><span className="sparkle-two">+</span></div><h3>A little less searching.<br />A lot more doing.</h3><p>Upload a file and its text will land here.<br />Ready to copy, search, or take with you.</p><span className="empty-result-hint"><ArrowRight size={13} />Start with a document on the left</span></div>
                  : !terminal(active.job.status) ? <div className="processing-state" role="status"><div className="processing-icon"><ScanText size={38} /><span /></div><h3>{active.job.status === 'QUEUED' ? 'A spot in the queue.' : 'Finding the words...'}</h3><p>{active.job.filename}</p><small>{active.job.status === 'QUEUED' ? 'Your document will start when a worker is available.' : 'Running locally. Larger scans can take a little longer.'}</small><button className="text-button" onClick={() => void cancel(active)}>Cancel extraction</button></div>
                  : active.job.status === 'FAILED' || active.job.status === 'CANCELLED' ? <div className="processing-state"><FileText size={40} className="muted" /><h3>{active.job.status === 'CANCELLED' ? 'Extraction cancelled.' : 'This file needs another look.'}</h3><p>{active.job.errorMessage ?? 'No result was created. You can upload the file again.'}</p>{active.job.errorCode && <code>{active.job.errorCode}</code>}</div>
                  : loadingResult ? <div className="processing-state" role="status"><LoaderCircle size={28} className="spin" /><p>Opening your text...</p></div>
                  : resultError ? <div className="processing-state" role="alert"><h3>Could not open the result.</h3><p>{resultError}</p><button className="text-button" onClick={() => setRetry(value => value + 1)}>Try again</button></div>
                  : result && tab === 'text' ? <><div className="document-title"><FileCheck2 size={17} /><span>{active.job.filename}</span></div>{result.warnings.map(warning => <div className="inline-warning" key={warning}>{warning}</div>)}<textarea className="text-output" aria-label="Extracted text" readOnly value={result.text} spellCheck={false} /></>
                  : result ? <div className="details"><h3>Document details</h3><dl><dt>Filename</dt><dd>{active.job.filename}</dd><dt>Detected format</dt><dd>{result.mediaType}</dd><dt>Processing mode</dt><dd>{active.job.ocrMode}</dd><dt>OCR language</dt><dd>{languageNames[active.job.language] ?? active.job.language}</dd><dt>Characters</dt><dd>{result.text.length.toLocaleString()}</dd><dt>Created</dt><dd>{new Date(active.job.createdAt).toLocaleString()}</dd><dt>Quality</dt><dd>{result.warnings.length ? 'Partial result - review warnings' : 'Extraction completed; accuracy is not guaranteed'}</dd></dl><button className="secondary-button" onClick={() => download(JSON.stringify({ document: active.job, ...result }, null, 2), `${active.job.filename}.json`, 'application/json')}><ArrowDownToLine size={15} />Download JSON</button><p>OCR is a transcription, not a guarantee. Verify names, dates, and amounts against your original.</p></div> : null}
              </div>
              <div className="result-footer"><span>{result ? `${result.text.length.toLocaleString()} characters` : 'Plain text. Endless possibilities.'}</span><span><ShieldCheck size={13} />No external OCR calls</span></div>
            </section>
          </div>
          <div className="feature-strip"><div><span><Layers3 size={18} /></span><p><strong>Many formats. One workflow.</strong>Documents, spreadsheets, slides & scans.</p></div><div><span><ScanText size={18} /></span><p><strong>Smart where it matters.</strong>Native text first. Local OCR when needed.</p></div><div><span><ShieldCheck size={18} /></span><p><strong>Free to run. Yours to keep.</strong>No subscriptions, API keys, or token bills.</p></div></div>
          <footer className="page-footer"><span>Made for the words worth keeping.</span><span>Powered by Apache Tika & Tesseract <Sparkles size={12} /></span></footer>
        </div>
      </main>
      <dialog ref={dialog} onCancel={() => setModal(null)} onClose={() => setModal(null)}>
        <div className="dialog-heading"><h2>{modal === 'formats' ? 'One home for your documents.' : 'Private by design. Clear by default.'}</h2><button className="icon-button" aria-label="Close dialog" onClick={() => setModal(null)}><X size={20} /></button></div>
        {modal === 'formats' ? <><p>Text Harbor uses Apache Tika for documents and local Tesseract for scans. Supported formats include:</p><div className="format-chips">{(capabilities?.formats ?? ['PDF', 'Word', 'Spreadsheets', 'Presentations', 'Images', 'Text']).map(format => <span key={format}>{format}</span>)}</div><h3>Choose the right mode</h3><p><strong>Smart:</strong> native text first, with OCR for scanned PDF pages and supported images. For PDFs containing both selectable text and screenshots on the same page, use <strong>whole-page PDF OCR</strong> to capture visual text that the native layer may miss.</p><p><strong>Native only:</strong> fastest for digital documents, but skips image text.</p><p>Handwriting, damaged documents, unusual encodings and complex layouts can produce imperfect output. Password-protected files and audio/video transcription are not supported. Archives are bounded; exceeding a limit gives a partial result, not a complete extraction claim.</p></> : <><p>Files go to your configured backend, not a paid cloud OCR service. In a local installation, extraction stays on your computer. If you deploy a remote backend, files go to that server.</p><p>Original uploads are deleted after processing. Results and job metadata expire after {capabilities?.retentionMinutes ?? 60} minutes, with cleanup running once a minute; you can also delete completed jobs immediately.</p><p>Job access tokens stay in this browser tab's session storage, not in URLs. Closing the tab may lose access to remaining jobs; they still expire on the server.</p><p>This edition is designed for a trusted local workspace. Add authentication, malware scanning, TLS and operational controls before exposing it publicly. No OCR engine can promise perfect accuracy.</p></>}
      </dialog>
    </div>
  )
}

function isEntry(value: unknown): value is Entry {
  if (!value || typeof value !== 'object' || !('job' in value) || !('accessToken' in value)
      || typeof value.accessToken !== 'string' || !value.job || typeof value.job !== 'object') return false
  const job = value.job
  return 'id' in job && typeof job.id === 'string' && 'filename' in job && typeof job.filename === 'string'
    && 'size' in job && typeof job.size === 'number' && 'status' in job && typeof job.status === 'string'
    && Object.hasOwn(statusNames, job.status) && 'warnings' in job && Array.isArray(job.warnings)
}
