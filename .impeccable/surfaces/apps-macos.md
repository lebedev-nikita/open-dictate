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
permissions, Live/Accurate mode, speech language, and start/finish, voice-edit,
cancel shortcuts. Dictionary, Replacements, History, and Settings are secondary pages. The
rail keeps the current shortcut and version visible.

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

Mode remains Operate in the established SwiftUI/AppKit world. `GoogleDriveSettingsView.swift` presents a native sheet after cloud has been read and known synced dictionary, replacements, dictation mode or model choices differ. `DriveSourceSelectionView` uses system headline/body/callout styles, semantic primary text, wrapping explanatory copy and a native `radioGroup` picker. The sheet is 480pt wide with 24pt padding and 20pt main-stack spacing. Cloud is selected initially; descriptions explain replacing this Mac's synced settings or publishing local settings to the other connected devices. One result-named confirm action has the default Enter shortcut; Cancel has the Escape shortcut. Cancellation or sheet dismissal disconnects and keeps local and cloud settings.

The durable pending-connection gate survives errors and restart. Confirmation re-downloads cloud and checks both sources against the presented choice before applying it; a changed source renews the prompt. Explicit choice overrides offline clocks. Empty or matching cloud settings proceed without a prompt, and ordinary automatic synchronization resumes after success. Existing connection controls and saved appearance remain in place.

Implementation authority: `GoogleDriveSettingsView.swift` and `GoogleDriveSync.swift`. Final native evidence is `.impeccable/review/drive-source-en-NSAppearanceNameAqua.png`, `.impeccable/review/drive-source-en-NSAppearanceNameDarkAqua.png`, `.impeccable/review/drive-source-ru-NSAppearanceNameAqua.png`, and `.impeccable/review/drive-source-ru-NSAppearanceNameDarkAqua.png`. These are production `NSHostingView` renders in English/Russian and both native appearances; they do not demonstrate a live sheet presentation or Google OAuth interaction. The supplied Swift verification reports 138 passing tests. No HTML detector ran on this native surface.

Finish reviewer disposition: **ship** for this narrow addition; all five finish-review sections passed, with no material fixes. No new token, shipping raster asset or visual world is introduced; captures remain review evidence.
