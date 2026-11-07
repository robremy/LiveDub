# LiveDub

Dubs English audio playing on your Android phone (e.g. YouTube) into a Dutch voice, fully on-device.

Pipeline: `AudioPlaybackCapture` → Vosk (offline English speech-to-text) → ML Kit (offline EN→NL) → Android TTS (Dutch).

## Build

1. Open this folder in Android Studio (Ladybug or newer) and let Gradle sync.
   Android Studio supplies the Gradle wrapper and SDK 35 on first sync.
2. Run on a physical device with Android 10+ (API 29+).

## First run

1. Tap **Start dubbing**. Grant the microphone and notification permissions.
2. The app downloads the English speech model (~130 MB) and the Dutch translation model (~30 MB) once.
3. Accept the screen-capture prompt. This is how Android allows audio playback capture. Nothing is recorded or stored.
4. Open YouTube and play an English video.

A Dutch voice must be installed: Settings → System → Languages → Text-to-speech (Google TTS) → Dutch.

## Notes and limits

- Latency is roughly 2–4 s. Long run-on speech is cut after 22 words (`MAX_WORDS`) so the lag does not grow.
- If the Dutch queue backs up, the voice speeds up, then drops the backlog (`MAX_BACKLOG`).
- The English is only *ducked* while the Dutch voice speaks, and some apps ignore ducking. Lowering YouTube's volume helps.
- Apps can opt out of playback capture. If YouTube is captured silently, no audio is available to the app.
- Quality: speech recognition uses the `en-us-0.22-lgraph` Vosk model. Misheard English words become odd Dutch, so recognition accuracy limits translation quality.
- Tweak `USAGE_*` in `DubService.buildRecord()` if some audio is not captured.
