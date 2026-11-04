# Reev privacy policy

_Last updated: 4 November 2026_

Reev is a keyboard for Android with voice dictation, published on Google Play by Sudo AI. This policy explains what Reev does with your data. In short: **Reev has no servers, no accounts, no analytics and no ads.**

## What Reev processes

**What you type.** Keystrokes are handled on your phone so they can be inserted into the text field you're using. Reev does not store, log or transmit what you type. To improve suggestions, Reev counts how often you use words. Counts for words already in its built-in dictionary live only in memory. Words that are *not* in the dictionary (for example names or slang) are saved, with a usage count, in Reev's private storage on your phone, up to 2,000 words. Reev never learns from password, PIN or incognito fields, or anything containing digits or symbols (so no emails, URLs, codes or numbers). You can delete the list at any time in Settings → **Forget learned words**.

**Your voice (microphone).** Reev only records during a dictation you start: while you hold the talk button, or after you tap it to lock recording on, until you tap again. The mic is unavailable in password, PIN and incognito fields. What happens to that audio depends on the engine you pick:

- **On-device engines (Parakeet Ultra, NVIDIA Parakeet TDT v2), the default.** Audio is transcribed on your phone and never leaves it. It isn't saved after the transcript is produced.
- **Cloud engines (ElevenLabs, Cartesia, Smallest AI, Sarvam), used only if you choose one and enter your own API key.** While you're dictating, audio streams over an encrypted connection (TLS) directly from your phone to that provider, and the transcript comes back. Reev doesn't send it anywhere else. The provider's own privacy policy applies to that audio:
  - [ElevenLabs](https://elevenlabs.io/privacy-policy)
  - [Cartesia](https://cartesia.ai/legal/privacy.html)
  - [Smallest AI](https://smallest.ai/privacy-policy)
  - [Sarvam](https://www.sarvam.ai/privacy-policy)

**API keys.** Keys you enter are encrypted with a key held in your phone's Android Keystore and stored only in Reev's private storage. They're excluded from Android backups, hidden from screenshots of the settings screen, and sent only to the provider they belong to, to authenticate your requests. Clear the field in Settings to delete a key.

**Model downloads.** When you download an on-device model, Reev fetches it from Hugging Face (huggingface.co). As with any download, Hugging Face sees a standard web request from your device.

## What Reev does not do

- No accounts, sign-in or user tracking.
- No analytics, crash reporting SDKs or advertising.
- No selling or sharing of data.
- It doesn't draw over other apps, and it doesn't use Android's accessibility service.

## Permissions

- **Microphone:** for dictation, only during a dictation you start.
- **Internet:** to download on-device models, and for cloud engines if you choose one.

## Children

Reev isn't directed at children under 13.

## Deleting your data

Everything Reev stores lives on your phone. Clear Reev's storage in Android Settings, or uninstall the app, and all of it is deleted, including learned words, settings, API keys and downloaded models.

## Contact

Questions: open an issue at <https://github.com/asispan/reev/issues>.
