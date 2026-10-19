# Use a ChatGPT plan for Luna

Save your OpenAI API key for transcription first. In Settings → Integrations,
turn on **ChatGPT** and authorize plan usage in your system browser.
Eligible accounts can use their plan allowance or credits for GPT-6 Luna text
transformation and Accurate post-processing. The model list remains Luna only.
Audio transcription and explicit AI history search still use your API key.

Review allowance, credits and app limits in
[ChatGPT settings](https://chatgpt.com/settings/usage). Credentials stay on that
device and are never synchronized by Drive.

If sign-in, model access or plan usage fails, reconnect or check ChatGPT settings.
Post-processing preserves the original transcript on failure. The app never
silently pays for a failed ChatGPT text request using your API key. Turning the
ChatGPT switch off explicitly returns text processing to the API key and attempts
remote revocation;
if revocation cannot be confirmed, also disconnect the app in ChatGPT settings.

## Protocol references

The integration uses the official public-client flow, without a client secret,
partner API key, Codex credentials or ChatGPT private endpoints:

- [Registration and signed identity validation](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Account registrations, token rotation and revocation](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Model catalog and completed streaming inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Supported requests and transcription exclusion](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Plan-use consent and error recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
