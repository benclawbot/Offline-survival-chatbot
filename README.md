# Offline Survival Chatbot

![Offline Survival Chatbot hero](.github/social-preview-v2.jpg)

GitHub social preview: [`.github/social-preview-v2.jpg`](.github/social-preview-v2.jpg) (1280 × 640, compact JPEG). Set this image in **Repository → Settings → General → Social preview** to update the link card.

**An offline-first Android chatbot for finding practical guidance in a searchable survival reference library.** It combines a compact on-device language model with the Essentials collection from [Survival LLM](https://github.com/anonnerdfrenzy/survival-llm). Once installed and set up, chat and reference lookup work without a network connection.

> **Important:** This is an experimental reference aid, not an emergency service or a substitute for professional medical, rescue, or safety advice. A small language model can misunderstand questions or produce incorrect guidance. In immediate danger, contact local emergency services when possible and follow official instructions.

## Why use it?

Phones are useful during travel, outages, and remote trips, but online search can fail when connectivity is weak or unavailable. This app keeps both the model and its reference library on the device, so you can ask plain-language questions and search prepared source material locally.

It is designed for people who want a portable reference for preparedness and practical topics, and who prefer a self-contained sideloaded app over a cloud chatbot or app-store installation. It does not send chat text to a server and the build requests no internet permission.

## Main features

- **On-device chat:** Runs Qwen 2.5 0.5B Instruct through LiteRT-LM on Android. The model is included in the APK; there is no separate model download.
- **Offline reference search:** Searches the Essentials FTS5/BM25 index on-device, retrieves passage text from the included data shards, and returns article title, source, URL, and license information to the chatbot.
- **Prepared survival and health material:** The Essentials collection includes emergency preparedness, first aid and emergency medicine, medical references, food and water safety, travel health, survival manuals, field sanitation, canning, repair, and appropriate technology material. See the bundled `SOURCES.md` for source and license details after first launch at the app data location.
- **All-in-one sideload:** One APK contains the app, model, and compressed reference library. Google Play is not required.
- **Private by default:** Chat and search run locally. The APK omits the Android internet permission; network search is unavailable.
- **Document support:** The base chat UI supports adding local documents to a conversation.
- **Source-aware answers:** The system prompt asks the model to search local references for survival, medical, first-aid, and safety questions and cite retrieved source titles and URLs.

## Included library

The app bundles the **Essentials** pack from Survival LLM, release `library-2026-10-28`. The downloaded archive is approximately 1.28 GB; its extracted content is approximately 3.23 GB. The APK also includes the model, so first-run setup copies the model and extracts the library into app-private storage. Keep the app open while this happens. Later starts use the installed files.

The library is primarily English-language. It is a curated offline collection, not the full web or full Wikipedia. Source content carries the licenses and attribution described in the bundled `SOURCES.md`; sources have different terms.

## Device and installation

- Android 12 or newer (minimum API 31)
- 64-bit ARM device recommended; built for `arm64-v8a`
- Allow installation from the file manager/browser used to open the APK
- APK size: approximately 2.4 GB
- Plan for several gigabytes of free space for Android’s app install and the extracted model and library; leave additional room for the OS and your other apps

Install the all-in-one APK, open **Offline Survival Chatbot**, and leave it open through the first-run extraction. The locally built APK is signed with a debug certificate for direct sideloading. It is suitable for installation, but future updates signed with a different key will not install over it; uninstalling first removes app data. A GitHub release has not yet been published.

## How local search works

The app uses the library’s included SQLite FTS5 index. It tokenizes the question into search terms, queries the index with BM25 ranking, reads the matching passage bytes from the corresponding text shard, and adds those passages—with their title, source, URL, and license—to the assistant’s tool result. This is lexical search, not a separately bundled vector embedding model; exact terminology in the question can affect retrieval quality.

## Build from source

Requirements: JDK 21, Android SDK with platform 35 and build tools 35.0.0, and sufficient disk space for the APK assets.

First fetch the pinned, checksum-verified model and Essentials pack, then build:

```bash
./scripts/fetch-offline-assets.sh
./gradlew :app:assembleDebug
```

The binary assets and APK are intentionally excluded from Git history because they total multiple gigabytes. The compact social preview image is `.github/social-preview-v2.jpg` (1280 × 640, compact JPEG); set it in **Repository → Settings → General → Social preview** to update the repository link card.

## Suggested repository topics

`android`, `offline-ai`, `on-device-llm`, `local-llm`, `survival`, `preparedness`, `emergency-reference`, `first-aid`, `litert`, `kotlin`, `jetpack-compose`

## Upstream projects and data

- App foundation: [LocalChat](https://github.com/NeuroMarshal/LocalChat)
- Survival reference library: [Survival LLM](https://github.com/anonnerdfrenzy/survival-llm)
- Runtime: [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
- Model: [Qwen 2.5 0.5B Instruct, LiteRT community build](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct)

The application source inherits the base project’s Apache License 2.0; see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Model and library data have their own licenses and terms. Refer to the included library attribution file and upstream model card before redistribution.
