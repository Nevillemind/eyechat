# EyeChat — Known Working State (May 24, 2026)

## What Works Right Now

### Full Pipeline (CONFIRMED WORKING)
1. ✅ **Glasses BLE → Hub** — QuickNote audio captured via temple hold
2. ✅ **Hub → EyeChat** — Broadcast IPC delivers LC3 frames
3. ✅ **EyeChat → Relay** — Audio sent to Flask relay via Tailscale (100.82.28.24:5555)
4. ✅ **Relay → Whisper** — whisper.cpp with base model, Metal GPU accelerated on M4 Mac mini
5. ✅ **Whisper → Display** — Accurate transcription displayed on G1 glasses

### Confirmed Test (May 24, 2026 3:44 PM EDT)
- **Input:** "okay Doug here we go something good is about to happen Yahweh is in charge and we're about to take it to the front this new whisper is going to be the fix"
- **Result:** 100% accurate transcription, displayed on glasses
- **Audio:** 10 seconds, 1,070 LC3 frames, 342,400 bytes PCM

---

## Components

### EyeChat App (v24 - WORKING)
- **Package:** com.bits.telechat
- **Source:** bits/eyechat/app/src/main/java/com/bits/telechat/MainActivity.kt
- **APK:** eyechat-v24-tailscale-restored.apk
- **Relay URL:** http://100.82.28.24:5555 (Tailscale)
- **Network:** Cleartext HTTP allowed for 100.82.28.24 in network_security_config.xml
- **HTTP Client:** OkHttpClient with ConnectionPool(0, 1, MILLISECONDS), 30s connect timeout
- **Audio:** LC3 decode → PCM → WAV, stream timeout 30s idle

### Hub App (v8 - WORKING)
- **Package:** io.texne.g1.hub
- **Source:** bits/courtsight/bridge/g1-basis-android/service/src/main/java/io/texne/g1/basis/service/G1Service.kt
- **APK:** hub-v4-quicknote.apk or hub-v5-defensive.apk (Hub v8 is current)
- **Library:** g1-basis-android (Maven Central)
- **Key:** Package name MUST be io.texne.g1.hub (NOT com.bits.g1hub)
- **Seq guard:** Commented out (was NOT the root cause of 190-byte issue)
- **Protocol:** QuickNote (0x21) — store-and-forward, host requests audio after release

### Relay Server (WORKING)
- **Source:** bits/eyechat/relay/server.py
- **Port:** 5555 (0.0.0.0)
- **STT:** whisper.cpp (/opt/homebrew/bin/whisper-cli) with ggml-base.bin model
- **Fallback:** Python whisper CLI with base model
- **Model path:** bits/eyechat/relay/models/ggml-base.bin
- **Pipeline:** Flask relay → Whisper → OpenClaw localhost:18789 → Doug response → glasses
- **Tailscale:** Mac mini 100.82.28.24, phone 100.66.86.104

### Glasses
- **Model:** Even G1.87
- **Left:** 4889E5
- **Right:** 755072
- **Protocol:** QuickNote = temple hold → 0x21 on release → host requests audio
- **Audio:** LC3 16kHz, frames 200 bytes each, BLE chunks 190 bytes (10-byte header stripped)
- **IMPORTANT:** Even official app MUST be force-closed (competes for BLE, causes recording cutoffs)

---

## Known Issues (NOT Blocking)

1. **190-byte audio mystery** — Some Hub versions only capture 1 BLE chunk (190 bytes). Workaround: use Hub v8 or earlier. Root cause unknown.
2. **Ghost QuickNote triggers** — Firmware/touch sensor sends spurious 0x21 events
3. **STOP DISPLAY unreliable** — Glasses firmware doesn't always acknowledge clear command
4. **Flask relay stability** — Development server, crashes occasionally. Production WSGI recommended.
5. **Tailscale DERP relay** — Phone connects via DERP (~87-268ms), not direct WireGuard. PMTU blackhole possible on large POSTs but not an issue for current audio sizes.

---

## BLE Protocol Reference
- 0x21 = QuickNote (store-and-forward audio)
- 0x1E = Dashboard/audio stream
- 0xF1 = Live mic (LC3 16kHz)
- 0x52/0x53 = Streaming text display
- 0xF5 = Touch/gesture events

---

## Critical Lessons
1. **Package name matters:** io.texne.g1.hub vs com.bits.g1hub caused silent IPC failure across v18-v24
2. **v17 source was destroyed by sed** — always backup APKs before ANY modification
3. **Hub git had NO commits May 17-21** — backup APKs are the only safety net
4. **Even app must be force-closed** — competes for BLE, causes recording issues
5. **whisper.cpp >> Python whisper** — 10-20x faster with Metal GPU, less hallucination
6. **base model >> tiny model** — tiny hallucinates badly on LC3 audio, base is accurate
