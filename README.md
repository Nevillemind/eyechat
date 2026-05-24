# EyeChat — BITS

Two-way voice communication through Even G1 smart glasses. Part of the BITS (Blockchain Integrative Technology Solutions) ecosystem.

## Status
**Working** — Full pipeline confirmed May 24, 2026.

Glasses → BLE LC3 → Hub → EyeChat → Relay → Whisper.cpp STT → AI Response → Glasses Display

## Documentation
- [Working State](docs/WORKING-STATE.md) — Exactly what works, components, versions
- [CourtSight Vision](docs/COURTSIGHT-VISION.md) — Legal AI assistant roadmap

## Quick Start
1. Install Hub APK (io.texne.g1.hub)
2. Install EyeChat APK (com.bits.telechat)
3. Force-close Even official app
4. Start relay: `python3 relay/server.py`
5. Connect glasses, hold temple to record

## Requirements
- Even G1 smart glasses
- Android phone
- Mac mini (or server) running relay + whisper.cpp
- Tailscale (for remote connectivity)
