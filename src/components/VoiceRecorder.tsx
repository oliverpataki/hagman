"use client";

import { useState, useEffect, useRef, useCallback } from "react";

// SpeechRecognition types (vendor-prefixed, not in all TS DOM versions)
type SRAlternative = { transcript: string };
type SRResult = { isFinal: boolean; length: number; [i: number]: SRAlternative };
type SREvent = { resultIndex: number; results: SRResult[] };
type SRErrEvent = { error: string };
type SR = {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  start: () => void;
  stop: () => void;
  onresult: ((e: SREvent) => void) | null;
  onerror: ((e: SRErrEvent) => void) | null;
  onend: (() => void) | null;
};
declare global {
  interface Window {
    SpeechRecognition?: new () => SR;
    webkitSpeechRecognition?: new () => SR;
  }
}

const LANGUAGES = [
  { code: "cs-CZ", label: "🇨🇿 Čeština" },
  { code: "sk-SK", label: "🇸🇰 Slovenčina" },
  { code: "en-US", label: "🇺🇸 English" },
  { code: "de-DE", label: "🇩🇪 Deutsch" },
  { code: "fr-FR", label: "🇫🇷 Français" },
  { code: "es-ES", label: "🇪🇸 Español" },
  { code: "pl-PL", label: "🇵🇱 Polski" },
  { code: "uk-UA", label: "🇺🇦 Українська" },
];

export default function VoiceRecorder() {
  const [isRecording, setIsRecording] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [interim, setInterim] = useState("");
  const [language, setLanguage] = useState("cs-CZ");
  const [mode, setMode] = useState<"browser" | "whisper">("browser");
  const [backendUrl, setBackendUrl] = useState("http://localhost:8000");
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showSettings, setShowSettings] = useState(false);
  const [audioLevel, setAudioLevel] = useState(0);
  const [totalWords, setTotalWords] = useState(0);

  const isRecordingRef = useRef(false);
  const recognitionRef = useRef<SR | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const animFrameRef = useRef<number>(0);

  // ─── Audio level analyser ────────────────────────────────────────────────
  const startAudioAnalysis = useCallback(
    async (existingStream?: MediaStream): Promise<MediaStream | null> => {
      try {
        const stream =
          existingStream ||
          (await navigator.mediaDevices.getUserMedia({ audio: true }));
        streamRef.current = stream;

        const ctx = new AudioContext();
        const source = ctx.createMediaStreamSource(stream);
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 128;
        source.connect(analyser);
        const data = new Uint8Array(analyser.frequencyBinCount);

        const tick = () => {
          analyser.getByteFrequencyData(data);
          const avg = data.reduce((a, b) => a + b, 0) / data.length;
          setAudioLevel(avg);
          animFrameRef.current = requestAnimationFrame(tick);
        };
        tick();
        return stream;
      } catch {
        setError(
          "Nelze získat přístup k mikrofonu. Povolte přístup v nastavení prohlížeče."
        );
        return null;
      }
    },
    []
  );

  const stopAudioAnalysis = useCallback(() => {
    cancelAnimationFrame(animFrameRef.current);
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    setAudioLevel(0);
  }, []);

  // ─── Browser SpeechRecognition mode ─────────────────────────────────────
  const startBrowserRecording = useCallback(async () => {
    const SRCtor = window.SpeechRecognition || window.webkitSpeechRecognition;

    if (!SRCtor) {
      setError(
        "Váš prohlížeč nepodporuje rozpoznávání řeči. Použijte Chrome nebo Edge, nebo přepněte na Whisper AI mód."
      );
      return;
    }

    const stream = await startAudioAnalysis();
    if (!stream) return;

    const rec = new SRCtor();
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = language;

    rec.onresult = (e: SREvent) => {
      let fin = "";
      let int = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) fin += e.results[i][0].transcript + " ";
        else int += e.results[i][0].transcript;
      }
      if (fin) {
        setTranscript((p) => p + fin);
        setTotalWords((p) => p + fin.trim().split(/\s+/).filter(Boolean).length);
      }
      setInterim(int);
    };

    rec.onerror = (e: SRErrEvent) => {
      if (e.error === "no-speech") return;
      setError(`Chyba: ${e.error}`);
      isRecordingRef.current = false;
      setIsRecording(false);
      stopAudioAnalysis();
    };

    rec.onend = () => {
      setInterim("");
      if (isRecordingRef.current) {
        try {
          rec.start();
        } catch {}
      }
    };

    recognitionRef.current = rec;
    isRecordingRef.current = true;
    setIsRecording(true);
    setError(null);
    rec.start();
  }, [language, startAudioAnalysis, stopAudioAnalysis]);

  const stopBrowserRecording = useCallback(() => {
    isRecordingRef.current = false;
    recognitionRef.current?.stop();
    recognitionRef.current = null;
    stopAudioAnalysis();
    setIsRecording(false);
    setInterim("");
  }, [stopAudioAnalysis]);

  // ─── Whisper backend mode ────────────────────────────────────────────────
  const startWhisperRecording = useCallback(async () => {
    const stream = await navigator.mediaDevices
      .getUserMedia({ audio: true })
      .catch(() => {
        setError("Nelze získat přístup k mikrofonu.");
        return null;
      });
    if (!stream) return;

    await startAudioAnalysis(stream);
    chunksRef.current = [];

    const mr = new MediaRecorder(stream);
    mr.ondataavailable = (e) => {
      if (e.data.size > 0) chunksRef.current.push(e.data);
    };
    mediaRecorderRef.current = mr;
    mr.start();
    isRecordingRef.current = true;
    setIsRecording(true);
    setError(null);
  }, [startAudioAnalysis]);

  const stopWhisperRecording = useCallback(async () => {
    const mr = mediaRecorderRef.current;
    if (!mr) return;

    isRecordingRef.current = false;
    setIsRecording(false);
    setIsProcessing(true);
    stopAudioAnalysis();

    await new Promise<void>((resolve) => {
      mr.onstop = () => resolve();
      mr.stop();
    });

    const blob = new Blob(chunksRef.current, { type: "audio/webm" });
    const form = new FormData();
    form.append("audio", blob, "recording.webm");
    form.append("language", language.split("-")[0]);

    try {
      const res = await fetch(`${backendUrl}/transcribe`, {
        method: "POST",
        body: form,
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: { text: string } = await res.json();
      const text = data.text.trim() + " ";
      setTranscript((p) => p + text);
      setTotalWords((p) => p + text.trim().split(/\s+/).filter(Boolean).length);
    } catch {
      setError(
        `Nelze se připojit k Whisper serveru (${backendUrl}). Ujistěte se, že backend běží.`
      );
    }

    setIsProcessing(false);
  }, [backendUrl, language, stopAudioAnalysis]);

  // ─── Toggle recording ────────────────────────────────────────────────────
  const toggleRecording = useCallback(async () => {
    if (isRecording || isProcessing) {
      if (mode === "browser") stopBrowserRecording();
      else await stopWhisperRecording();
    } else {
      if (mode === "browser") await startBrowserRecording();
      else await startWhisperRecording();
    }
  }, [
    isRecording,
    isProcessing,
    mode,
    startBrowserRecording,
    stopBrowserRecording,
    startWhisperRecording,
    stopWhisperRecording,
  ]);

  // ─── Keyboard shortcut (Space) ───────────────────────────────────────────
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if (e.code === "Space") {
        e.preventDefault();
        toggleRecording();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [toggleRecording]);

  // ─── Cleanup on unmount ──────────────────────────────────────────────────
  useEffect(() => {
    return () => {
      isRecordingRef.current = false;
      recognitionRef.current?.stop();
      stopAudioAnalysis();
    };
  }, [stopAudioAnalysis]);

  // ─── Clipboard ───────────────────────────────────────────────────────────
  const copy = useCallback(() => {
    const text = transcript.trim();
    if (!text) return;
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }, [transcript]);

  const clear = useCallback(() => {
    setTranscript("");
    setInterim("");
  }, []);

  // ─── Derived values ───────────────────────────────────────────────────────
  const orbScale = isRecording ? 1 + (audioLevel / 255) * 0.25 : 1;
  const glow = Math.floor((audioLevel / 255) * 50);

  return (
    <div className="min-h-screen bg-[#0a0a0a] text-white flex flex-col select-none">
      {/* ── Header ── */}
      <header className="flex items-center justify-between px-5 py-4 border-b border-white/5">
        <span className="text-xl font-bold bg-gradient-to-r from-purple-400 to-blue-400 bg-clip-text text-transparent tracking-tight">
          hagman
        </span>
        <div className="flex items-center gap-3">
          {totalWords > 0 && (
            <span className="text-xs text-gray-600 tabular-nums">
              {totalWords.toLocaleString("cs-CZ")} slov
            </span>
          )}
          <button
            onClick={() => setShowSettings((v) => !v)}
            className="w-8 h-8 rounded-full flex items-center justify-center text-gray-500 hover:text-white hover:bg-white/10 transition-all"
            aria-label="Nastavení"
          >
            <svg
              viewBox="0 0 24 24"
              className="w-4 h-4"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
            >
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </button>
        </div>
      </header>

      {/* ── Settings panel ── */}
      {showSettings && (
        <div className="mx-4 mt-3 p-4 bg-white/[0.04] border border-white/10 rounded-2xl space-y-4">
          <div>
            <label className="text-[11px] font-medium text-gray-500 uppercase tracking-wider">
              Jazyk
            </label>
            <select
              value={language}
              onChange={(e) => setLanguage(e.target.value)}
              className="mt-1.5 w-full bg-white/10 border border-white/10 rounded-lg px-3 py-2 text-sm text-white appearance-none cursor-pointer"
              style={{ background: "#1a1a1a" }}
            >
              {LANGUAGES.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="text-[11px] font-medium text-gray-500 uppercase tracking-wider">
              Způsob přepisu
            </label>
            <div className="mt-1.5 flex gap-2">
              <button
                onClick={() => setMode("browser")}
                className={`flex-1 py-2 rounded-xl text-sm font-medium transition-all ${
                  mode === "browser"
                    ? "bg-purple-600 text-white"
                    : "bg-white/10 text-gray-400 hover:bg-white/15"
                }`}
              >
                🌐 Prohlížeč
              </button>
              <button
                onClick={() => setMode("whisper")}
                className={`flex-1 py-2 rounded-xl text-sm font-medium transition-all ${
                  mode === "whisper"
                    ? "bg-purple-600 text-white"
                    : "bg-white/10 text-gray-400 hover:bg-white/15"
                }`}
              >
                🤖 Whisper AI
              </button>
            </div>
            <p className="mt-2 text-[11px] text-gray-600">
              {mode === "browser"
                ? "Funguje v Chrome/Edge bez instalace. Vyžaduje internet."
                : "Přesný AI model běžící lokálně. Vyžaduje Python backend."}
            </p>
          </div>

          {mode === "whisper" && (
            <div>
              <label className="text-[11px] font-medium text-gray-500 uppercase tracking-wider">
                Whisper server
              </label>
              <input
                type="text"
                value={backendUrl}
                onChange={(e) => setBackendUrl(e.target.value)}
                className="mt-1.5 w-full bg-white/10 border border-white/10 rounded-lg px-3 py-2 text-sm text-white font-mono"
                placeholder="http://localhost:8000"
              />
            </div>
          )}
        </div>
      )}

      {/* ── Main area ── */}
      <div className="flex-1 flex flex-col items-center justify-center px-4 py-8 gap-6">
        {/* Orb */}
        <div className="relative flex items-center justify-center">
          {isRecording && (
            <>
              <div
                className="animate-pulse-ring absolute rounded-full"
                style={{
                  width: 160,
                  height: 160,
                  background: "rgba(239,68,68,0.25)",
                }}
              />
              <div
                className="animate-pulse-ring-delay absolute rounded-full"
                style={{
                  width: 160,
                  height: 160,
                  background: "rgba(239,68,68,0.15)",
                }}
              />
            </>
          )}

          <button
            onClick={toggleRecording}
            disabled={isProcessing}
            style={{
              transform: `scale(${orbScale})`,
              transition: "transform 0.08s ease-out, box-shadow 0.15s ease",
              boxShadow: isRecording
                ? `0 0 ${20 + glow}px rgba(239,68,68,0.7), 0 0 ${40 + glow * 2}px rgba(239,68,68,0.3)`
                : "0 0 30px rgba(124,58,237,0.5), 0 0 60px rgba(37,99,235,0.2)",
            }}
            className={`relative w-36 h-36 rounded-full flex flex-col items-center justify-center gap-1 transition-colors
              ${
                isRecording
                  ? "bg-red-600 active:bg-red-700"
                  : "bg-gradient-to-br from-purple-600 to-blue-600 hover:from-purple-500 hover:to-blue-500 active:scale-95"
              }
              ${isProcessing ? "opacity-60 cursor-wait" : "cursor-pointer"}
            `}
          >
            {isProcessing ? (
              <div className="w-9 h-9 border-2 border-white/30 border-t-white rounded-full animate-spin" />
            ) : (
              <svg
                className="w-11 h-11 text-white"
                fill="currentColor"
                viewBox="0 0 24 24"
              >
                <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" />
                <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" />
              </svg>
            )}
            <span className="text-[10px] text-white/60 font-semibold uppercase tracking-wider">
              {isProcessing ? "čekejte" : isRecording ? "nahrávám" : "mikrofon"}
            </span>
          </button>
        </div>

        {/* Hint */}
        <p className="text-gray-600 text-sm text-center">
          {isProcessing
            ? "Přepisuji zvuk pomocí Whisper AI…"
            : isRecording
            ? "Mluvte… klikněte znovu nebo stiskněte mezerník pro zastavení"
            : "Klikněte nebo stiskněte mezerník"}
        </p>

        {/* Error */}
        {error && (
          <div className="w-full max-w-lg bg-red-500/10 border border-red-500/20 rounded-xl p-4">
            <p className="text-red-400 text-sm">{error}</p>
            {error.includes("Whisper") && (
              <p className="text-gray-600 text-xs mt-2">
                Spusťte backend:{" "}
                <code className="text-purple-400 font-mono">
                  cd backend && python main.py
                </code>
              </p>
            )}
          </div>
        )}

        {/* Transcript */}
        {transcript || interim ? (
          <div className="w-full max-w-lg">
            <div className="bg-white/[0.04] border border-white/10 rounded-2xl p-5 min-h-[100px]">
              <p className="text-white text-base leading-relaxed">
                {transcript}
                {interim && (
                  <span className="text-gray-500 italic">{interim}</span>
                )}
              </p>
            </div>
            <div className="flex gap-2.5 mt-3">
              <button
                onClick={copy}
                disabled={!transcript.trim()}
                className={`flex-1 flex items-center justify-center gap-2 py-3 rounded-xl text-sm font-medium transition-all
                  ${
                    copied
                      ? "bg-green-600 text-white"
                      : "bg-purple-600 hover:bg-purple-700 text-white disabled:opacity-40"
                  }`}
              >
                {copied ? (
                  <>
                    <svg
                      className="w-4 h-4"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth="2.5"
                    >
                      <polyline points="20 6 9 17 4 12" />
                    </svg>
                    Zkopírováno!
                  </>
                ) : (
                  <>
                    <svg
                      className="w-4 h-4"
                      fill="none"
                      viewBox="0 0 24 24"
                      stroke="currentColor"
                      strokeWidth="2"
                    >
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                    </svg>
                    Kopírovat
                  </>
                )}
              </button>
              <button
                onClick={clear}
                className="px-4 py-3 rounded-xl text-sm font-medium bg-white/[0.06] hover:bg-white/10 text-gray-400 transition-all"
              >
                Smazat
              </button>
            </div>
          </div>
        ) : (
          !isRecording &&
          !isProcessing && (
            <div className="w-full max-w-lg text-center text-gray-700 text-sm py-10 border border-dashed border-white/[0.06] rounded-2xl">
              Text se zobrazí zde po skončení nahrávání
            </div>
          )
        )}
      </div>

      {/* ── Footer ── */}
      <footer className="px-5 py-3 border-t border-white/5 flex items-center justify-between">
        <span className="text-[11px] text-gray-700">
          {mode === "browser" ? "🌐 Prohlížeč" : "🤖 Whisper AI"}
        </span>
        <span className="text-[11px] text-gray-700">
          {LANGUAGES.find((l) => l.code === language)?.label ?? language}
        </span>
      </footer>
    </div>
  );
}
