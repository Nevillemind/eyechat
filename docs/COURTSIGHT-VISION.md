# CourtSight — Vision & Architecture (May 24, 2026)

## The Goal
Real-time AI legal assistant on smart glasses for pro se courtroom representation.

**What the user sees:**
- Standing in court, glasses capture what's being said
- AI transcribes, analyzes, and surfaces relevant legal information
- Display shows: precedents, definitions, recommended objections, case citations
- All hands-free, phone in pocket

## Current State (EyeChat Pipeline — CONFIRMED WORKING)
```
Glasses mic → BLE LC3 → Hub → EyeChat → Relay → Whisper.cpp → Text → Display
```

## CourtSight Target Architecture
```
Glasses mic (live stream 0xF1)
    → BLE LC3 → Hub app
    → EyeChat/CourtSight app
    → Relay server
    → Whisper.cpp (STT)
    → Legal AI Engine
        → Black's Law Dictionary (local)
        → Courthouse5 API (case law)
        → Virginia Code / Statutes (local)
        → Case precedent database
        → Alex's sovereign AI agent farm (future)
    → Formatted response
    → Glasses display (streaming text 0x52/0x53)
```

## Key Differences from EyeChat

### 1. Audio Input: QuickNote → Live Stream
- EyeChat uses QuickNote (0x21) — hold temple, record, release, send
- CourtSight needs **live stream (0xF1)** — continuous LC3 16kHz capture
- Must process in real-time chunks, not wait for recording to end

### 2. Response: Single text → Streaming legal analysis
- EyeChat sends one message back
- CourtSight needs streaming text (0x52/0x53 protocol) — scroll updates as AI processes
- Auto-paging like CourtSight v1.0 (8 seconds per page)

### 3. Knowledge Base: General → Legal-specific
- Need local Black's Law Dictionary database
- Courthouse5 integration for case law search
- Virginia-specific statutes and case law
- Objection reference guide (7 Virginia pre-trial categories from CourtSight v1.0)

### 4. AI Engine: ChatGPT → Legal AI
- Current: Doug (general AI) responds to transcribed text
- Target: Specialized legal AI that identifies:
  - What's happening in court right now
  - What objections to raise
  - What precedents apply
  - What the judge/prosecutor is referencing
  - What the user should say next

## Implementation Phases

### Phase 1: CourtSight + EyeChat Audio Merge (Current)
- Use EyeChat audio pipeline as-is
- CourtSight app receives courtroom audio
- Transcribe with whisper.cpp
- Display AI legal analysis on glasses
- **Status: Audio pipeline WORKING, CourtSight v1.0 text display WORKING**

### Phase 2: Live Streaming Audio
- Switch from QuickNote (0x21) to Live Mic (0xF1)
- Process continuous LC3 stream in chunks
- Real-time transcription with sentence boundaries
- Streaming display updates

### Phase 3: Legal Knowledge Base
- Local Black's Law Dictionary (SQLite)
- Courthouse5 API integration
- Virginia Code database
- Case precedent search

### Phase 4: Sovereign AI Agent Farm (Alex's Project)
- Connect to Alex's private local AI server farm
- Hundreds of AI agents acting as attorneys
- Real-time legal analysis and strategy
- Fully private, no cloud dependency

## What CourtSight v1.0 Already Has
- ✅ 7 Virginia pre-trial categories displayed on G1
- ✅ Auto-paging (8 seconds per page)
- ✅ Text display confirmed working
- ✅ Source: bits/courtsight/android-courtsight/

## What EyeChat v24 Adds
- ✅ Audio capture from glasses
- ✅ LC3 decode pipeline
- ✅ Whisper.cpp accurate transcription
- ✅ Two-way communication (audio in, text out)
- ✅ Tailscale remote connectivity

## Merging = CourtSight v2.0
Take CourtSight's legal display + EyeChat's audio pipeline = real-time courtroom AI assistant.
