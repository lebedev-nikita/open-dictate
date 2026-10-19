# macOS application

Mode: Operate. Native SwiftUI settings and AppKit menu bar utility for macOS 14+,
with no third-party UI dependency. Inherit OpenDictate's black, graphite, white
microphone identity through system typography, SF Symbols, native controls,
keyboard navigation and English/Russian copy. The user's instruction delegates
technology and implementation choices: beautiful, clean, light and fast.

## Direction contract

THESIS: Dictate into the app already in use through a global keyboard shortcut.

OWN-WORLD: Dark graphite settings canvas and sidebar, white microphone, SF Symbols
and native controls; no marketing imagery or decorative motion. System accent
color is retained for selected controls and keyboard focus. The captured system
accent is blue; this is a native control state, not an added brand color.

STORY: The first Dictation page presents the API key, microphone and Accessibility
permissions, Live/Accurate mode, transcription and shared text models, speech language, and start/finish, voice-edit,
cancel shortcuts. Dictionary, Replacements, History, and Settings are secondary pages. The
rail keeps the current shortcut and version visible. Settings groups Google Drive and ChatGPT in the localized Integrations subsection.

FIRST VIEWPORT: Default settings content is 790 × 650pt, with a fixed 190pt
sidebar and a scrollable body. The NSWindow minimum is 740 × 620pt including
window chrome; the SwiftUI content minimum is 740 × 590pt. The compact review
uses 740 × 590pt content. Rail padding is 20pt and body padding is 28pt. At the
default size, setup, mode, language and shortcut rows form one continuous page;
at the minimum, the longer Russian supporting copy scrolls vertically. Headings
use 27pt semibold system type; navigation uses 13pt regular/selected-medium type.
The selected row's tonal fill and the native keyboard focus ring are distinct.

The menu bar always shows a microphone. During recording, its filled white
silhouette is centered on a red circle. Preparation,
idle and processing show the template microphone; processing adds no ellipsis.
The recording indicator is a
nonactivating AppKit panel that cannot become key or main. During preparation and recording,
its red finish control invokes `model.stop()` without taking keyboard focus;
only processing ignores mouse events. Escape cancels only during a
session. Localized Edit commands use the AppKit responder chain for normal
text-field and editor shortcuts.

RECORDING INDICATOR: The pinned compact treatment shows only a microphone and
red finish control from preparation through recording. Its dark capsule is
44 × 22pt; the SF `mic.fill` symbol is 9pt medium and the red circle is 14pt.
Each is centered in a 22 × 22pt half of the capsule, keeping the finish circle
concentric with the right end and its top, bottom and trailing insets equal at 4pt.
The centered white stop square is 4.5pt with a 1pt corner, inside a 16pt circular
hit area. Processing shows only a centered native circular `ProgressView` at
mini control size on a 22 × 22pt capsule; idle is
hidden. No visible text, timer, waveform, or shortcut hint belongs to this
indicator. `windowBackgroundColor` and the dark scheme supply its background;
the native panel shadow remains. Position it at the current screen's visible
frame center, 28pt above the bottom edge. Localized finish label/help and
processing accessibility label carry the action and busy state.

FORM: Direct native adaptation of the established Android settings surface;
code-led within the established visual world, with no approved visual comp and
no composition tournament. Web fonts, pixel dimensions and hover lift are not
native implementation tokens.

FINISH: Ship verdict after the material review findings were resolved. DESIGN.md
and its sidecar record the built native adaptation. Native surfaces have no
detector pass; the review uses implementation inspection and actual screenshots.
The rc.1 indicator refinement passed a finish review; rc.4 halves both dimensions
and makes its finish control available during preparation. Production-view
raster tests and offscreen native captures verify the new dimensions and spacing.
Retain the red circle, white stop square, dark capsule, localized accessibility,
and nonactivation. This was a code-led refinement in the existing world, with no
comp or direction roll.

## Implementation evidence

- `apps/macos/Sources/OpenDictate/SettingsView.swift`: five pages, system type,
  semantic colors, native control styles, scrolling, and passive status content.
- `apps/macos/Sources/OpenDictate/OpenDictateApp.swift`: window dimensions,
  menu commands, recording panel ownership and nonactivation.
- `apps/macos/Sources/OpenDictate/RecordingIndicator.swift`: indicator states,
  dimensions, capsule background, finish control, accessibility and panel focus
  behavior.
- `.impeccable/review/macos-dictation-en.png` and
  `.impeccable/review/macos-dictation-ru.png`: default Dictation page in both
  languages.
- `.impeccable/review/macos-dictation-ru-min.png`: Russian Dictation at the compact
  content size, with vertical scrolling.
- `.impeccable/review/macos-settings-en.png`,
  `.impeccable/review/macos-dictionary-en.png`, and
  `.impeccable/review/macos-history-en.png`: secondary pages, including a saved
  dictionary term and empty-history state.
- `.impeccable/review/macos-indicator-recording.png` and
  `.impeccable/review/macos-indicator-processing.png`: the reviewed compact
  recording and processing indicators.

The committed indicator captures document the earlier rc.1 geometry. The rc.4
size and spacing are checked by production-view render tests and offscreen
NSHostingView captures, with no change to panel nonactivation. Panel focus,
mouse behavior and localized accessibility are grounded in the SwiftUI/AppKit
implementation.

## Asset provenance

`apps/macos/scripts/generate-icon.swift` draws the near-black rounded square and
white microphone as a native code adaptation of `docs/mark.svg` and the existing
OpenDictate identity. Packaging produces the icon raster sizes and `.icns` in
build output. No generated shipping raster assets are committed to the repository;
review screenshots are evidence rather than shipping artwork.

## Theme choice

A native menu button beside the version at the bottom of the sidebar offers System, Light and Dark appearance.
The choice is saved in local preferences and applies to all five settings pages.
Dark preserves the prior default; System follows macOS. Semantic AppKit surfaces
and foreground colors keep the navigation selection readable in either theme.
The nonactivating recording indicator retains its dark appearance.

## Dictation settings

Mode remains Operate. `SettingsView.swift` retains the native Dictation page, speech-language controls and recognition hints in Dictionary, and places the shared Text model / Текстовая модель selector beside transcription models. Post-processing remains a separate Accurate-only section before shortcuts. There is no transcription prompt control or editor. `GoogleDriveSettingsView.swift` retains the existing synchronization controls. Native system typography, semantic surfaces, fixed rail and scrollable body remain the visual authority.

Earlier shared-selector full Settings evidence is `.impeccable/review/text-model-macos-{accurate-en-false,accurate-ru-false,accurate-ru-true,live-en-true}.png`. These four isolated localOnly production `NSHostingView` captures at 1000 × 900pt were viewed and valid; the test-runner footer version 16.0 is not product-version evidence. That finish review passed the shared text-model selector with disposition **ship** and no material fixes. Earlier preset component captures remain historical evidence. Verification and limits are recorded under Shared text-model selector.

## Waveform indicator choice

Settings saves a choice between Microphone (the existing compact indicator,
retained by default) and Waveform. The waveform follows the user's image:
50 × 22pt black capsule, ten centered white bars, and a subtle 0.5pt white border
at 14% opacity. Bars are 1.5pt wide, separated by 1.5pt; measured input levels
map from 2pt silence marks to 10pt peaks, with square-root scaling. New input
appears at the right, and older values move left. Ten normalized levels stay
in memory and are cleared at the start/end of each session.

The whole waveform capsule is the finish action, including during preparation;
processing replaces its bars with the shared compact circular mini spinner.
The panel never activates or takes editor focus. The existing visibility switch
hides either style. The native picker and guidance are localized in English and
Russian. Linear 40ms height interpolation follows audio updates and is disabled
by Reduce Motion. Production-view raster tests, offscreen native captures,
actual Settings/preview interaction and a local microphone check ground this
variant. No captured audio or level history is persisted.

## RC7 refinement

Both indicator styles and processing spinners scale uniformly by 1.25, including icons, bars, borders, spacing and hit targets. Nominal compact size is 55 × 27.5pt and waveform size is 62.5 × 27.5pt. Native panel bounds round fractional points as needed. Finish uses an accessibility-labelled native NSView that refuses first responder and dispatches the action after mouse-up. The panel remains nonactivating. Escape in the shortcut recorder clears and persists the binding; disabled shortcuts are skipped by global registration.

## Local word replacements extension

Mode remains Operate. Replacements is a distinct fifth sidebar page with the `arrow.left.arrow.right` SF Symbol, preserving the fixed rail, system typography, native theme choice, and scrollable settings body. Dictionary continues to hold recognition hints. `ReplacementSettingsView.swift` uses the existing 27pt semibold heading, supporting secondary copy, a native global switch, rounded-border source/replacement fields, and native Add/Save/Cancel buttons. Editor groups use 12pt spacing and the page uses 20pt spacing. Saved rules use checkbox controls, source above replacement, and accessible borderless pencil/trash actions, separated by native dividers.

English/Russian copy wraps vertically. Blank fields disable Add/Save, rejected entries show validation guidance, and storage errors preserve saved data and disable saving. The empty state provides a source → replacement example. Editing focuses the source field; the settings body supports long lists and compact windows. Footer guidance explains whole-word/phrase matching without case sensitivity, exact replacement spelling, next-dictation application in Live and Accurate, and device-local storage. No synchronization control is present; synchronization is deferred until Google Drive support exists.

Finish reviewer disposition: **ship**, with no material findings. `SettingsView.swift` and `ReplacementSettingsView.swift` are implementation authority; `.impeccable/review/macos.png` is the native replacement-page capture. The review confirmed no clipping and used native code/captures without an HTML detector. No shipping raster assets or new visual tokens were introduced.

## Speech language picker extension

Mode remains Operate in the established native world. The Dictation page replaces its five-option language menu with a summary button and SwiftUI/AppKit popover (340pt), containing 64 ISO language choices and native multi-selection checkboxes. The popover uses 16pt padding, 12pt stack spacing and a 280pt scrolling list. Its headline, helper, explicit caption above the rounded search field, automatic-detection reset and selected-count footer preserve system typography, semantic surfaces and native accent/focus. Rows pair interface-localized and native names and stay alphabetically ordered in the interface language. Helper, search caption, native-name captions, count and empty-result text use semantic primary foreground to retain readable contrast in light appearance; this is a picker-specific refinement.

Search accepts English, Russian, native names and ISO codes. Checkboxes save immediately without closing; Automatic detection clears all selections. Done and Enter dismiss; Escape dismisses while retaining saved choices. The trigger summarizes up to two native names plus the remaining count and exposes the full selection through help and accessibility. The picker follows the existing System/Light/Dark preference and closes/disables during recording. Stored `auto`, `ru`, `en`, `uk` and `ru-en` choices migrate to the local language set; the immutable session context supplies both existing transcription paths. The selection adds no synced preference or raster asset.

Implementation authority: `SpeechLanguagePicker.swift`, `SettingsView.swift`, `OpenDictateCore/SpeechLanguage.swift` and `Storage.swift`. Actual native interaction verified search in English/Russian/native names/codes, multiple selection without closing, automatic reset, Done, Enter, Escape, light/dark appearance and a local microphone check. Final evidence is `.impeccable/review/macos-languages-en-dark-retina.png` and `.impeccable/review/macos-languages-ru-light-retina.png`: offscreen production `NSHostingView` captures at 2× (680px for 340pt), held as ignored review artifacts rather than shipping artwork.

Finish reviewer disposition: **ship** for inspected UI quality and flow after both contrast fixes were resolved. Both captures fit without clipping; sampled Russian light text exceeds 4.5:1 contrast. No web detector ran on this native surface. Recognition across all 64 catalog languages was not network-tested.

## Current recording and menu-bar geometry

The current Waveform capsule is 62.5 × 28.32pt after the uniform 1.25 scale.
Ten bars are 1.875pt wide and range from 2.5pt in silence to 17.5pt at full input;
the full-level waveform spans 38.63 × 17.5pt. Both width and height ratios
between capsule and waveform equal φ. Processing is a shared 27.5 × 27.5pt
circle for either style. These current production values supersede earlier
waveform and RC7 dimensions above; the production view and render tests are
geometry authority.

`MenuBarIcon.swift` keeps the microphone in every phase on a stable 20 × 20pt
canvas. Only `.recording` shows a white `mic.fill` on an `NSColor.systemRed`
circle. The visible silhouette is 14pt high; its ink bounds determine centering
so transparent SF Symbol padding cannot produce unequal opposite insets.
The tightly cropped native symbol is cached at high resolution in memory,
avoiding scale-dependent symbol padding without a persistent raster asset.
Other phases retain the native template `mic`. No title is added while processing.
`MenuBarIconTests` covers phase mapping, stable icon dimensions, a filled white
microphone, a circular red background and equal opposite insets in Aqua and Dark
Aqua at 1×, 2× and 8× using production-image renders. This check covers the icon,
not live microphone, TCC or network transcription behavior.

## Google Drive source-choice extension

Mode remains Operate in the established SwiftUI/AppKit world. `GoogleDriveSettingsView.swift` presents a native sheet after cloud has been read and known synced dictionary, replacements, dictation mode, recognition models, the shared text model or post-processing settings differ. `DriveSourceSelectionView` uses system headline/body/callout styles, semantic primary text, wrapping explanatory copy and a native `radioGroup` picker. The sheet is 480pt wide with 24pt padding and 20pt main-stack spacing. Cloud is selected initially; descriptions explain replacing this Mac's synced settings or publishing local settings to the other connected devices. One result-named confirm action has the default Enter shortcut; Cancel has the Escape shortcut. Cancellation or sheet dismissal disconnects and keeps local and cloud settings.

The durable pending-connection gate survives errors and restart. Confirmation re-downloads cloud and checks both sources against the presented choice before applying it; a changed source renews the prompt. Explicit choice overrides offline clocks. Empty or matching cloud settings proceed without a prompt, and ordinary automatic synchronization resumes after success. Existing connection controls and saved appearance remain in place.

Implementation authority: `GoogleDriveSettingsView.swift` and `GoogleDriveSync.swift`. Final native evidence is `.impeccable/review/drive-source-en-NSAppearanceNameAqua.png`, `.impeccable/review/drive-source-en-NSAppearanceNameDarkAqua.png`, `.impeccable/review/drive-source-ru-NSAppearanceNameAqua.png`, and `.impeccable/review/drive-source-ru-NSAppearanceNameDarkAqua.png`. These are production `NSHostingView` renders in English/Russian and both native appearances; they do not demonstrate a live sheet presentation or Google OAuth interaction. The supplied Swift verification reports 138 passing tests. No HTML detector ran on this native surface.

Finish reviewer disposition: **ship** for this narrow addition; all five finish-review sections passed, with no material fixes. No new token, shipping raster asset or visual world is introduced; captures remain review evidence.

## Post-processing extension

`PostProcessingSettingsView.swift` presents a separate Accurate-only section between transcription and shortcuts. Live hides its section and divider while retaining saved configuration. The native global switch starts off, saves immediately and collapses options while disabled. Expanded content retains 14pt spacing and system controls: punctuation starts on; Format values has a popup action and separate switch. Switching values off retains examples. Reset settings restores punctuation-only options while preserving global enabled state. No final-prompt editor or plus/Save prompt actions remain.

Format values opens `ValueFormattingMenu`, a native popover with system typography, semantic surfaces, ten type checkboxes, editable rounded-border example fields and suggestion menus. Its scrollable list retains localized inline validation. System defaults resets only the draft; Cancel/Escape discards it and Done/Enter applies a valid draft and enables value formatting. The popover uses 20pt padding, a 420pt width and a 370pt list viewport. Keep native focus, scrolling and appearance behavior rather than importing web controls.

`OpenDictateCore/ValueFormatting.swift` derives examples from system region and number/date/time conventions, independently of interface-language override. First enabling values or saving the menu stores concrete examples so both platforms retain the same choices. Shared `postprocessing-rules.json` owns type definitions, suggestions and instruction rules.

Post-processing and transformation use the shared immutable text-model snapshot; the sole built-in choice is GPT-6 Luna. Ordinary Accurate final text and Accurate retry use post-processing; Live bypasses it while preserving saved configuration, and voice editing remains separate. Cancellation and failures preserve the existing delivery and transcript safeguards.

Drive synchronizes the atomic version-1 `postprocessingOptions` configuration, including enabled state, punctuation, value-formatting state and stored examples. The generated prompt is assembled from shared `postprocessing-rules.json` rules at request time and never synchronized. Legacy arbitrary prompt text is discarded while its enabled state migrates; legacy cloud entries seed at clock zero so they cannot override an edited structured configuration. The shared `textProcessingModel` key remains independent; retired `textModel` and `postprocessing` entries do not restore prompt editing.

Current native evidence includes `.impeccable/review/value-formats-macos-ru-top.png`, `value-formats-macos-en-bottom.png` and `postprocessing-macos-ru-true.png` in the same review directory. These are isolated production-component captures, not a full OS flow or installer evidence. Supplied verification reports 203 passing Swift tests and successful universal packaging. Fresh finish disposition: **ship** at the documentation-fix verification scope. The sole stale-documentation finding is resolved; the prior UI/source inspection passed with all nine required captures viewed and valid. No HTML detector, visual token or shipping raster asset is introduced. Fresh TCC, hotkeys, insertion, real Drive/API requests and model-output quality remain outside this UI scope.

## History timing captions

Mode remains Operate. `SettingsView.historyPage` delegates saved rows to `HistoryEntryView.swift`. Compact timing captions sit between the date/action row and selectable transcript, retaining the existing 8pt stack spacing, divider and native copy/delete actions. Captions wrap vertically in system caption type with semantic primary foreground; dates remain secondary. Localized full accessibility names expand Transcr. / Post-proc. and Транскр. / Постобр. Live shows only literal Live. Unknown metadata and skipped post-processing add no caption; seconds use the interface locale, one decimal and <0.1 / <0,1 below 100 ms.

`AppModel.swift`, `Storage.swift` and `OpenDictateCore/HistoryTiming.swift` are behavior authority: monotonic durations cover complete Accurate network awaits, including upload/server/response, excluding recording, local replacements and delivery. Accurate retry uses the same boundaries; failed post-processing attempts retain elapsed time. Optional local JSON fields preserve older history and remain outside Drive synchronization and AI search.

Fresh finish disposition: **ship** after the sole contrast fix changed the new caption from secondary to primary foreground. Both final recaptures are `.impeccable/review/history-timing-macos-{en,ru}.png`: isolated production `NSHostingView` rows in English dark at 568pt width and Russian light at 464pt width. Supplied verification reports 193 passing Swift tests, JSON compatibility/persistence and deterministic request-boundary coverage. These ignored component captures do not demonstrate the full Settings page, TCC, hotkeys, insertion or real OpenAI requests. No HTML detector, new visual tokens or shipping raster assets are involved.

## Shared text-model selector

Mode remains Operate in the established SwiftUI/AppKit world. `SettingsView.swift` places a localized Text model / Текстовая модель row after Live/Accurate models and before speech languages. An `HStack` pairs the visible label with a right-aligned native `Picker`, using `.labelsHidden().fixedSize()` and `.disabled(model.isActive)`. `TextProcessingModel.defaults` owns the sole built-in GPT-6 Luna choice. The existing system type, semantic colors, theme, rail and scrollable body remain the visual authority.

The shared Text model / Текстовая модель selector sits beside transcription models on both platforms and stays visible in Live for transformation. Its built-in list contains only GPT-6 Luna (`gpt-6-luna`), selected by default. This saved choice is independent of post-processing options: transformation and post-processing use the immutable session-start model snapshot; Accurate retry captures it at invocation. AI history search remains fixed to `gpt-6-luna`. Drive synchronizes the new `textProcessingModel` key and validates its model ID before atomic import. The retired `textModel` key remains discarded so old RC data cannot restore it. `Storage.swift`, `OpenDictateCore/SettingsSync.swift`, `AppModel.swift` and the request builders are behavior authority.

Fresh finish disposition: **ship**, with no material fixes. All four full Settings captures `.impeccable/review/text-model-macos-{accurate-en-false,accurate-ru-false,accurate-ru-true,live-en-true}.png` were viewed and valid. Isolated localOnly production `NSHostingView` renders at 1000 × 900pt cover English/Russian, Accurate off/expanded and Live with the shared selector visible and post-processing hidden. The runner footer version 16.0 is not installer-version evidence. Supplied verification reports 194 passing Swift tests, successful universal packaging and version checks, core request-builder/merge coverage, and a session test changing the saved selection during recording to verify snapshot behavior. Shared configuration checks passed all five tests. Native captures have no HTML detector; no new token or shipping raster asset is introduced. TCC, hotkeys, insertion, physical devices, real Drive and paid OpenAI requests were not verified.

## ChatGPT plan connection

Mode remains Operate in the established SwiftUI/AppKit world. `SettingsView.swift` places `ChatGPTSettingsView.swift` beside Google Drive in the localized Integrations / Интеграции subsection of `generalPage`, outside Dictation and visible regardless of dictation mode. Both provider rows use a 24pt vector mark, 12pt icon-to-label gap and regular trailing native Toggle. A leading VStack with 4pt spacing places the provider name above its caption: For synchronization / Для синхронизации for Drive and For working with text / Для работы с текстом for ChatGPT. These new captions use system caption type with primary foreground for readable light appearance. ChatGPT retains the 12pt outer stack and conditional 8pt supporting stack with a 36pt leading inset. Native appearance, focus behavior, fixed rail, scrolling, English/Russian wrapping and the sole built-in GPT-6 Luna text model remain unchanged.

Turning on starts browser sign-in; turning off disconnects. The switch disables during active dictation or authorization, and without a saved transcription API key unless already connected, preserving sign-out after key deletion. Busy authorization keeps it checked and disabled, with progress, Waiting for ChatGPT guidance and Cancel. Missing-key guidance, reconnect, error/revocation guidance and first-login confirmation remain; reconnect requires the key and disables during active or busy states. The normal connected row contains only the provider name, helper and switch. Plan status, account identity, account menu and usage link are removed.
Audio transcription and explicit AI history search continue to use the API key. Plan-request failure has no automatic paid API fallback; failed post-processing keeps the original transcript. Credentials stay in device-only, non-synchronizing Keychain storage and never enter Drive synchronization. Implementation authority is `ChatGPTSettingsView.swift`, `GoogleDriveSettingsView.swift`, `ChatGPTConnection.swift`, `ChatGPTSession.swift`, `ChatGPTAuthorization.swift`, `SettingsView.swift`, `AppModel.swift`, `Network.swift` and `OpenDictateCore/ChatGPTProtocol.swift`; `docs/chatgpt-plan.md` records user guidance and official protocol references. `ChatGPTMark.swift` shares original vector geometry with Android's `ic_chatgpt_24.xml`; source comments and third-party notices preserve provenance.

Fresh native UI/source finish disposition: **ship** at the helper-contrast fix scope. The initial macOS helper contrast finding is resolved by primary foreground; the other inspected UI/source sections passed. Native evidence is `.impeccable/review/integrations-macos-{en-ready,ru-locked}.png`, isolated production provider components with synthetic connection state. Source verifies the shared Integrations placement; these are not full Settings captures. Supplied verification reports 200 passing Swift tests and successful universal packaging, alongside the green Android suites and three instrumentation configurations. Captures remain ignored review evidence; no HTML detector, new visual token or shipping raster asset is involved. This UI change does not claim real ChatGPT login, eligibility, paid inference, TCC, hotkeys, insertion or physical-device verification.
