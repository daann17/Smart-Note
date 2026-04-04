# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SmartNote is a full-stack personal knowledge management platform with collaborative editing. It consists of a Vue 3 frontend and a Spring Boot 3 backend communicating via REST APIs and WebSockets.

## Development Commands

### Frontend (`frontend/`)
```bash
npm install        # Install dependencies
npm run dev        # Start dev server (proxies /api and /ws-collab to backend)
npm run build      # Production build
```

### Backend (`backend/`)
```bash
# Windows
..\start.ps1

# Or directly with Maven
mvn spring-boot:run
mvn -DskipTests compile   # Compile only
mvn -DskipTests package   # Build JAR
```

### Environment Setup
Backend reads from `backend/.env` (not committed). Required variables:
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — PostgreSQL connection
- `OPENAI_API_KEY`, `OPENAI_BASE_URL` — AI integration (defaults to DeepSeek API)
- `SERVER_PORT` (default: 8081)
- `SMARTNOTE_EXPORT_PDF_FONT_PATH` — Font path for PDF export

Frontend proxies:
- `/api/*` → `VITE_API_PROXY_TARGET` (default: `http://localhost:8080`)
- `/ws-collab` → WebSocket target

**Note:** The default backend port in `application.yml` is `8081`, but the frontend proxy defaults to `8080` — check `.env` / `frontend/.env` for the actual port in use.

## Architecture

### Frontend (Vue 3 + TypeScript + Vite)

**State management** uses Pinia with three stores (`frontend/src/stores/`):
- `note.ts` — active note, note list, editing state
- `notebook.ts` — notebook list and selected notebook
- `tag.ts` — tag management

**Routing** (`frontend/src/router/index.ts`) uses Vue Router with route guards enforcing `requiresAuth` and `requiresAdmin` meta flags. The JWT token and role are stored in session via `utils/session.ts`.

**Key views** (`frontend/src/views/`):
- `NoteView.vue` — main editor view; hosts the Markdown editor and note list sidebar
- `KnowledgeGraphView.vue` — interactive force-directed graph of notes and relations
- `ShareView.vue` — public share page (no auth required)
- `AdminView.vue` — admin dashboard (role-gated)

**Real-time collaboration** uses Yjs CRDTs synced over STOMP WebSocket (`/ws-collab`). The editor component (`src/components/MarkdownEditor.vue`) integrates CodeMirror 6 with a Yjs binding and tracks online collaborators.

### Backend (Spring Boot 3 + JPA + PostgreSQL)

Standard layered architecture under `backend/src/main/java/com/smartnote/`:
- `controller/` — REST endpoints and WebSocket handlers
- `service/` — business logic
- `repository/` — Spring Data JPA repositories
- `entity/` — JPA entities (Note, Notebook, User, Tag, etc.)
- `dto/` — request/response DTOs
- `config/` — Spring Security, WebSocket, CORS, cache configuration
- `util/` — JWT helpers, file utilities

**Security:** JWT-based auth via Spring Security. Tokens are validated per-request via a filter in `config/`. The `/api/auth/**` and `/share/**` endpoints are public; everything else requires a valid token.

**AI integration:** Uses Spring AI configured against the DeepSeek API (OpenAI-compatible). Controllers in `AIController` handle streaming chat responses.

**Export pipeline:** Notes can be exported as Markdown, PDF (OpenHTMLtoPDF), or Word (Apache POI). PDF export requires a font path configured via `SMARTNOTE_EXPORT_PDF_FONT_PATH`.

**Caching:** Caffeine in-memory cache (max 500 entries, 10-minute TTL) configured via Spring Cache.

**Database:** PostgreSQL with JPA `ddl-auto: update` — schema auto-migrates on startup. No migration tool (Flyway/Liquibase) is used.

## Key Patterns

- The frontend uses `axios` with a request interceptor (likely in `utils/request.ts` or similar) to attach the JWT `Authorization` header to all `/api` calls.
- Collaborative editing sessions are note-scoped: the Yjs document room is identified by note ID over the STOMP WebSocket.
- The knowledge graph is built server-side from note link relationships and served as graph data to the frontend.
- Version history and trash are soft-delete patterns managed in the backend service layer.
