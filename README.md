# Text Harbor

A local-first document text extraction workspace built with React, Java 21,
Spring Boot, Apache Tika and Tesseract. No paid APIs, cloud OCR, or LLM tokens.

The application extracts text from supported documents and images, not literally
every binary format. Encrypted documents, unsupported files and unreadable scans
produce explicit errors or warnings. It does not transcribe audio/video.

## Milestones

1. Foundation: repository, dependency manifests, and project structure.
2. Extraction service: bounded background jobs, local parsers/OCR, and tests.
3. React workspace: upload, status, results, and accessible interactions.
4. Release: container packaging, end-to-end checks, and operating instructions.

Setup instructions and the exact supported-format contract are added with the
release milestone.
