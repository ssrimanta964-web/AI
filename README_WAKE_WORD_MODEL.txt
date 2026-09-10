This folder needs a Vosk speech model folder that you provide yourself —
I cannot download this for you (no network access in my environment).

Steps:

1. Go to https://alphacephei.com/vosk/models
2. Download a SMALL English model — look for one with "small" in the name,
   e.g. something like "vosk-model-small-en-us-0.15". Do NOT use one of the
   large (1GB+) models for a first attempt — they will make your APK huge
   and may not even load reliably on a phone.
3. Unzip it on your computer. You'll get a FOLDER (not a single file)
   containing subfolders like "am", "conf", "graph", etc.
4. Rename that folder to exactly: model
5. Place the whole folder here, so the path looks like:
   app/src/main/assets/model/am/...
   app/src/main/assets/model/conf/...
   app/src/main/assets/model/graph/...
   (etc — whatever subfolders came in the zip)

Why a small model: Vosk's small models are around 40-50MB, which is large
for an app but workable. Large models are 1GB+ and are not realistic to
bundle into an APK for personal use.

If this "model" folder is missing or incomplete, the app should report an
error when starting rather than silently failing — check ListeningService
if you don't see that behavior.

Note on how "wake word" works with Vosk in this app: unlike Picovoice,
Vosk is a full speech-to-text engine, not a dedicated wake-word detector.
This app continuously transcribes whatever it hears and checks if the
word "peter" appears in the transcribed text. This means:
- It uses more battery than a dedicated wake-word engine would.
- It technically "hears" and transcribes everything while listening, not
  just the wake word — the transcription just gets ignored unless it
  contains "peter". This is a real, meaningful difference from Picovoice
  and worth being aware of for your own sense of what the app is doing.
