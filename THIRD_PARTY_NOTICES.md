# Third-party software

Text Harbor's MIT license applies to its application code, not to third-party
libraries, executables, language data or container base images.

| Project | License / notices |
|---|---|
| Apache Tika | [Apache-2.0](https://github.com/apache/tika/blob/main/LICENSE) |
| Apache PDFBox | [Apache-2.0 and bundled notices](https://github.com/apache/pdfbox/blob/trunk/LICENSE.txt) |
| Apache POI | [Apache-2.0 and bundled notices](https://github.com/apache/poi/blob/trunk/LICENSE) |
| Spring Boot / Spring Framework | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/main/LICENSE.txt) |
| Apache Maven Wrapper | Apache-2.0; license headers remain in the wrapper scripts |
| Tesseract / trained language data | [Apache-2.0](https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE) |
| React / React DOM | [MIT](https://github.com/facebook/react/blob/main/LICENSE) |
| Vite | [MIT](https://github.com/vitejs/vite/blob/main/LICENSE) |
| Lucide | [ISC and upstream icon notices](https://lucide.dev/license) |
| TypeScript | [Apache-2.0](https://github.com/microsoft/TypeScript/blob/main/LICENSE.txt) |
| Vitest, Testing Library, jsdom | Their respective MIT licenses |
| Playwright | [Apache-2.0](https://github.com/microsoft/playwright/blob/main/LICENSE) |
| Eclipse Temurin / OpenJDK | GPL-2.0 with Classpath Exception and distribution notices |
| Nginx and OS packages | Licenses/notices distributed within the container images |

The Maven dependency artifacts retain their own `META-INF` license and notice
files. npm dependencies retain license files in their packages. Preserve upstream
notices when redistributing compiled bundles, executables or container images.
Review the exact locked dependency versions and transitive licenses for your
distribution; this list is not a complete software bill of materials.
