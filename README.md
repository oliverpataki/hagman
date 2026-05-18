# hagman

Bezplatná alternativa k Wispr Flow — hlasové diktování v prohlížeči nebo s AI Whisper modelem.

## Funkce

- 🎙️ Nahrávání hlasu jedním kliknutím nebo mezerníkem
- 🌐 **Prohlížeč mód** — Web Speech API, žádná instalace (Chrome/Edge)
- 🤖 **Whisper AI mód** — přesný lokální AI model, funguje offline
- 📋 Kopírování textu do schránky jedním kliknutím
- 📱 Instalovatelná jako PWA na telefon i desktop
- 🌍 Podpora čeština, slovenština, angličtina a dalších jazyků

## Rychlý start

### Webová aplikace (bez instalace)

```bash
npm install
npm run dev
```

Otevřete http://localhost:3000 v Chrome nebo Edge.

### Whisper AI backend (volitelný, pro lepší přesnost)

```bash
cd backend
pip install -r requirements.txt
python main.py
```

Backend poběží na http://localhost:8000. V aplikaci přepněte na **Whisper AI mód** v nastavení.

## Instalace na telefon (PWA)

1. Otevřete aplikaci v Chrome na Androidu nebo Safari na iOS
2. Klepněte na „Přidat na plochu" / „Add to Home Screen"
3. Aplikace se nainstaluje jako nativní app

## Nasazení zdarma (Vercel)

```bash
npx vercel
```

## Konfigurace Whisper backendu

| Proměnná | Výchozí | Popis |
|---|---|---|
| `WHISPER_MODEL` | `base` | Velikost modelu: `tiny`, `base`, `small`, `medium`, `large` |
| `WHISPER_DEVICE` | `cpu` | `cpu` nebo `cuda` (pokud máte GPU) |
| `PORT` | `8000` | Port serveru |

Větší model = lepší přesnost, ale pomalejší.
