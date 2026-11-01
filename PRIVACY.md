# Reev privacy policy

_Last updated: 1 November 2026_

Reev is a keyboard for Android with voice dictation, developed by Asis Panda. This policy explains what Reev does with your data. In short: **Reev has no servers, no accounts, no analytics and no ads.**

## What Reev processes

**What you type.** Keystrokes are handled on your phone so they can be inserted into the text field you're using. Reev does not store, log or transmit what you type. To improve suggestions, Reev keeps a small list of words you use often. That list is stored only on your phone, in Reev's private storage.

**Your voice (microphone).** Reev only records while you hold the talk button, or after you tap it to lock recording on. What happens to that audio depends on the engine you pick:

- **On-device engines (Parakeet Ultra, NVIDIA Parakeet TDT v2), the default.** Audio is transcribed on your phone and never leaves it. It isn't saved after the transcript is produced.
- **Cloud engines (ElevenLabs, Cartesia, Smallest AI, Sarvam), used only if you choose one and enter your own API key.** While you're dictating, audio streams over an encrypted connection (TLS) directly from your phone to that provider, and the transcript comes back. Reev doesn't send it anywhere else. The provider's own privacy policy applies to that audio:
  - [ElevenLabs](https://elevenlabs.io/privacy-policy)
  - [Cartesia](https://cartesia.ai/legal/privacy.html)
  - [Smallest AI](https://smallest.ai/privacy-policy)
  - [Sarvam](https://www.sarvam.ai/privacy-policy)

**API keys.** Keys you enter are stored only in Reev's private storage on your phone. They're excluded from Android backups and sent only to the provider they belong to, to authenticate your requests.

**Model downloads.** When you download an on-device model, Reev fetches it from Hugging Face (huggingface.co). As with any download, Hugging Face sees a standard web request from your device.

## What Reev does not do

- No accounts, sign-in or user tracking.
- No analytics, crash reporting SDKs or advertising.
- No selling or sharing of data.
- It doesn't draw over other apps, and it doesn't use Android's accessibility service.

## Permissions

- **Microphone:** for dictation, only while you're talking.
- **Internet:** to download on-device models, and for cloud engines if you choose one.

## Children

Reev isn't directed at children under 13.

## Deleting your data

Everything Reev stores lives on your phone. Clear Reev's storage in Android Settings, or uninstall the app, and all of it is deleted, including learned words, settings, API keys and downloaded models.

## Contact

Questions: open an issue at <https://github.com/asispan/reev/issues>.
