# Release Notes

This file summarizes user-facing changes. It was started with the chat-to-image feature below;
earlier changes are not backfilled here (see `git log` for full history).

## 2026-08-11 — Chat-to-image prompt generation

Added a chat-assisted workflow for generating ComfyUI image prompts, available in both the
VSCode extension and the Web Admin Panel. Instead of writing a ComfyUI-style prompt by hand, you
can describe the image you want in plain language and have Ollama turn it into a usable prompt,
which then feeds directly into the existing image generation form.

### Added

- **API**: `POST /api/projects/{projectId}/ai/generate-image-prompt` — takes an optional chat
  history and the current message, returns a generated prompt (`{ "prompt": "..." }`). Requires
  project membership or admin. Each call is recorded in `generation_jobs` (type
  `ollama_image_prompt`).
- **VSCode extension**: a "チャットでプロンプトを作成" chat section in the **Let's Blog: Generate
  Image** panel. The generated prompt auto-fills the panel's prompt field; existing manual
  prompt entry and image generation are unchanged.
- **Web Admin Panel**: the same chat-to-prompt workflow inside the **AI・アセット** tab's
  **アセット画像生成** panel.

### Changed

- None — this is purely additive to the existing image generation flow
  (`POST /api/ai/image`); manually-written prompts continue to work exactly as before.

### Breaking changes

None.

See [docs/FEATURES_AND_USAGE.md](FEATURES_AND_USAGE.md#image-generation) for the full usage guide.
