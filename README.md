# SmartNote

SmartNote is a knowledge-note application built around Markdown editing, CRDT collaboration, note sharing, search, and visualization.

## Current Features

- User authentication with JWT-based login and route protection
- Notebook and note management, tags, trash, recent notes, and history rollback
- Markdown editing with CodeMirror 6
- Real-time collaboration based on Yjs CRDT
- Note search with notebook, tag, and date range filters
- Knowledge graph visualization
- Share links, extraction codes, comments, and collaborative editing for shared notes
- User profile basics: nickname, bio, phone, and birthday
- Admin center for overview and user management
- Export to Markdown, PDF, and Word

## Stack

- Frontend: Vue 3, TypeScript, Vite, Pinia, Vue Router, Ant Design Vue, CodeMirror 6, Yjs
- Backend: Spring Boot 3, Spring Security, JWT, Spring Data JPA, STOMP WebSocket
- Database: PostgreSQL
- Cache: Caffeine

## Quick Start

### Backend

```bash
cd backend
mvn spring-boot:run
```

For local or server deployment, prefer putting secrets in `backend/.env` and starting with:

```bash
cd backend
chmod +x start.sh
./start.sh
```

Use `backend/.env.example` as the template for your own `backend/.env`.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Default addresses:

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`

## Verification

```bash
cd backend
mvn -DskipTests compile

cd ../frontend
npm run build
```

## Secrets

- Commit `backend/src/main/resources/application.yml` and `backend/src/main/resources/application.example.yml` only with environment-variable placeholders.
- Put local secrets in environment variables or the ignored file `backend/.env`.
- Do not commit database passwords, API keys, or production endpoints to Git history.

## PDF Export On Linux

- Set `SMARTNOTE_EXPORT_PDF_FONT_PATH` to a readable `.ttf` file if the server needs stable PDF export with Chinese text.
- Recommended examples: `NotoSansSC-Regular.ttf` or another CJK TrueType font installed on the server.
- If no custom font is configured, SmartNote now falls back to renderer or system fonts instead of failing the export request.

```bash
export SMARTNOTE_EXPORT_PDF_FONT_PATH=/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf
```
