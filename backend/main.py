"""
hagman — Whisper AI backend
Spuštění: python main.py
Nebo:     uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="hagman Whisper backend")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["POST", "GET"],
    allow_headers=["*"],
)

# Lazy-load model to avoid startup delay
_model = None


def get_model():
    global _model
    if _model is None:
        try:
            from faster_whisper import WhisperModel
            model_size = os.environ.get("WHISPER_MODEL", "base")
            device = os.environ.get("WHISPER_DEVICE", "cpu")
            print(f"[hagman] Načítám Whisper model '{model_size}' na {device}…")
            _model = WhisperModel(model_size, device=device, compute_type="int8")
            print("[hagman] Model načten.")
        except ImportError:
            raise HTTPException(
                status_code=500,
                detail="faster-whisper není nainstalovaný. Spusťte: pip install -r requirements.txt",
            )
    return _model


@app.get("/")
def health():
    return {"status": "ok", "service": "hagman Whisper backend"}


@app.post("/transcribe")
async def transcribe(
    audio: UploadFile = File(...),
    language: str = Form(default="cs"),
):
    if not audio.content_type or not audio.content_type.startswith("audio/"):
        if not audio.filename or not any(
            audio.filename.endswith(ext) for ext in [".webm", ".wav", ".mp3", ".ogg", ".m4a"]
        ):
            raise HTTPException(status_code=400, detail="Nepodporovaný formát audia.")

    model = get_model()
    data = await audio.read()

    with tempfile.NamedTemporaryFile(
        suffix=Path(audio.filename or "audio.webm").suffix or ".webm",
        delete=False,
    ) as tmp:
        tmp.write(data)
        tmp_path = tmp.name

    try:
        lang = language if language != "auto" else None
        segments, info = model.transcribe(
            tmp_path,
            language=lang,
            beam_size=5,
            vad_filter=True,
        )
        text = " ".join(seg.text.strip() for seg in segments)
        return {
            "text": text,
            "language": info.language,
            "duration": round(info.duration, 2),
        }
    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8000))
    print(f"[hagman] Backend spuštěn na http://localhost:{port}")
    uvicorn.run(app, host="0.0.0.0", port=port)
