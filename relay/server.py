#!/usr/bin/env python3
"""
BITS EyeChat Relay Server
Bridges EyeChat Android app ↔ OpenClaw (Doug) directly.
Runs on the Mac mini. No Telegram dependency.

When George sends a message via EyeChat:
1. EyeChat app → relay /api/send
2. Relay stores message
3. Relay calls OpenClaw cron wake to notify Doug instantly
4. Doug processes and responds
5. Doug pushes response to relay /api/incoming
6. EyeChat polls relay → displays on glasses
"""

from flask import Flask, request, jsonify
from flask_cors import CORS
import uuid
import time
import json
import threading
import subprocess
import tempfile
import os

app = Flask(__name__)
CORS(app)

# ── Message store ──
messages = []
last_george_id = ""

# ── OpenClaw Gateway ──
OPENCLAW_PORT = 18789
OPENCLAW_TOKEN = None  # Will read from config if needed

# ── Whisper transcription ──
# Uses OpenAI Whisper API for speech-to-text
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")
WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"

@app.route("/api/transcribe", methods=["POST"])
def transcribe_audio():
    """Receive WAV audio from EyeChat, transcribe via Whisper"""
    if not OPENAI_API_KEY:
        return transcribe_local(request.get_data())
    
    try:
        # Save to temp file
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(request.get_data())
            tmp_path = f.name
        
        # Use curl for reliable multipart upload
        import subprocess
        result = subprocess.run(
            ["curl", "-s", "-X", "POST", WHISPER_API_URL,
             "-H", f"Authorization: Bearer {OPENAI_API_KEY}",
             "-F", f"file=@{tmp_path}",
             "-F", "model=whisper-1",
             "-F", "language=en"],
            capture_output=True, text=True, timeout=60
        )
        os.unlink(tmp_path)
        
        if result.returncode != 0:
            print(f"[whisper] curl error: {result.stderr}")
            return jsonify({"text": "", "error": result.stderr}), 500
        
        data = json.loads(result.stdout)
        text = data.get("text", "").strip()
        print(f"[whisper]: {text}")
        return jsonify({"text": text, "error": None})
    
    except Exception as e:
        import traceback
        print(f"Whisper API error: {e}")
        traceback.print_exc()
        return jsonify({"text": "", "error": str(e) or type(e).__name__}), 500


def transcribe_local(wav_data: bytes):
    """Fallback: use local whisper CLI"""
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(wav_data)
            tmp_path = f.name
        
        # Try whisper.cpp first (10-20x faster, Metal GPU accelerated)
        whisper_cpp_model = os.path.join(os.path.dirname(__file__), "models", "ggml-base.bin")
        if os.path.exists(whisper_cpp_model):
            result = subprocess.run(
                ["/opt/homebrew/bin/whisper-cli", "-m", whisper_cpp_model, "-f", tmp_path, "-l", "en", "--no-timestamps"],
                capture_output=True, text=True, timeout=15
            )
            os.unlink(tmp_path)
            text = result.stdout.strip()
            if text:
                print(f"[whisper-cpp]: {text}")
                return jsonify({"text": text, "error": None})
            print(f"[whisper-cpp] no output, stderr: {result.stderr[:200]}")
        else:
            print(f"[whisper-cpp] model not found at {whisper_cpp_model}, falling back to Python whisper")

        # Fallback to Python whisper (slower)
        result = subprocess.run(
            ["whisper", tmp_path, "--model", "base", "--language", "en", "--output_format", "txt", "--output_dir", tempfile.gettempdir()],
            capture_output=True, text=True, timeout=30
        )
        os.unlink(tmp_path)
        
        txt_path = tmp_path.replace(".wav", ".txt")
        if os.path.exists(txt_path):
            with open(txt_path) as f:
                text = f.read().strip()
            os.unlink(txt_path)
            print(f"[whisper-local]: {text}")
            return jsonify({"text": text, "error": None})
        
        return jsonify({"text": "", "error": "no transcription output"}), 500
    except Exception as e:
        return jsonify({"text": "", "error": str(e)}), 500

@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "messages": len(messages)})

@app.route("/api/send", methods=["POST"])
def send_message():
    """Receive message from EyeChat app (from George)"""
    global last_george_id
    data = request.json
    text = data.get("message", "").strip()
    sender = data.get("from", "george")

    if not text:
        return jsonify({"error": "empty message"}), 400

    msg_id = str(uuid.uuid4())
    msg = {
        "id": msg_id,
        "from": sender,
        "text": text,
        "timestamp": time.time()
    }
    messages.append(msg)
    last_george_id = msg_id

    print(f"[{sender}]: {text}")

    # ── INSTANT PUSH: Wake OpenClaw immediately ──
    # Instead of waiting for polling, trigger cron wake
    try:
        wake_url = f"http://localhost:{OPENCLAW_PORT}/api/cron/wake"
        wake_payload = {
            "text": f"EYECHAT: George says: {text}",
            "mode": "now"
        }
        threading.Thread(
            target=lambda: subprocess.run(
                ["/usr/bin/curl", "-s", "-X", "POST", wake_url,
                 "-H", "Content-Type: application/json",
                 "-d", json.dumps(wake_payload)],
                capture_output=True, timeout=5
            )
        ).start()
    except Exception as e:
        print(f"Wake failed: {e}")

    return jsonify({"status": "sent", "id": msg_id})

@app.route("/api/poll", methods=["GET"])
def poll_messages():
    """EyeChat app polls for new messages from Doug"""
    after_id = request.args.get("after", "")
    
    result = []
    found = False
    for msg in messages:
        if found:
            result.append(msg)
        if msg["id"] == after_id:
            found = True
    
    if not after_id:
        result = messages[-10:]
    
    return jsonify(result)

@app.route("/api/incoming", methods=["POST"])
def incoming_from_doug():
    """Doug (OpenClaw) pushes responses here"""
    data = request.json
    text = data.get("text", "").strip()
    
    if not text:
        return jsonify({"error": "empty"}), 400
    
    msg_id = str(uuid.uuid4())
    msg = {
        "id": msg_id,
        "from": "doug",
        "text": text,
        "timestamp": time.time()
    }
    messages.append(msg)
    
    print(f"[doug]: {text}")
    return jsonify({"status": "queued", "id": msg_id})

@app.route("/api/last-george", methods=["GET"])
def last_george_message():
    """Returns the last message from George (for OpenClaw polling)"""
    george_msgs = [m for m in messages if m["from"] == "george"]
    if george_msgs:
        return jsonify(george_msgs[-1])
    return jsonify({"id": "", "from": "", "text": ""})

if __name__ == "__main__":
    print("=" * 50)
    print("BITS EyeChat Relay Server")
    print("Running on http://0.0.0.0:5555")
    print("Direct pipeline to OpenClaw on localhost:18789")
    print("=" * 50)
    app.run(host="0.0.0.0", port=5555, debug=False)
