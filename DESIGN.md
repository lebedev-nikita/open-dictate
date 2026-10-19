---
name: OpenDictate
description: The dark public website system, with scoped native Android and macOS settings.
colors:
  ink: "#080808"
  ink-deep: "#030303"
  panel: "#171717"
  panel-raised: "#242424"
  white: "#ffffff"
  fog: "#c6c6c6"
  line: "#363636"
  signal: "#ffffff"
  signal-deep: "#e8e8e8"
  signal-hover: "#f0f0f0"
  secondary: "#e3e3e3"
typography:
  webDisplay:
    fontFamily: "Literata, Georgia, serif"
    fontSize: "clamp(52px, 5.2vw, 76px)"
    fontWeight: 500
    lineHeight: 1.12
    letterSpacing: "-0.025em"
  display:
    fontFamily: "Unbounded, Arial, sans-serif"
    fontSize: "clamp(48px, 6.35vw, 92px)"
    fontWeight: 500
    lineHeight: 0.98
    letterSpacing: "-0.04em"
  headline:
    fontFamily: "Unbounded, Arial, sans-serif"
    fontSize: "clamp(42px, 5vw, 72px)"
    fontWeight: 500
    lineHeight: 1.08
    letterSpacing: "-0.04em"
  title:
    fontFamily: "Unbounded, Arial, sans-serif"
    fontSize: "22px"
    fontWeight: 500
    lineHeight: 1.25
    letterSpacing: "-0.03em"
  body:
    fontFamily: "Golos Text, Segoe UI, sans-serif"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "SFMono-Regular, Cascadia Code, Roboto Mono, monospace"
    fontSize: "11px"
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: "0.06em"
  button:
    fontFamily: "Golos Text, Segoe UI, sans-serif"
    fontSize: "17px"
    fontWeight: 600
    lineHeight: 1.55
  macos-heading:
    fontFamily: "system-ui, -apple-system, sans-serif"
    fontSize: "27pt"
    fontWeight: 600
  macos-navigation:
    fontFamily: "system-ui, -apple-system, sans-serif"
    fontSize: "13pt"
    fontWeight: 400
  macos-brand:
    fontFamily: "system-ui, -apple-system, sans-serif"
    fontSize: "15pt"
    fontWeight: 600
rounded:
  wave: "3px"
  small: "8px"
  inset-control: "9px"
  control: "12px"
  control-group: "13px"
  panel: "16px"
  full: "999px"
  macos-navigation: "7pt"
  macos-editor: "8pt"
spacing:
  control-inset: "4px"
  compact: "8px"
  small: "12px"
  control: "20px"
  panel: "28px"
  block: "40px"
  section-mobile: "92px"
  section-desktop: "130px"
  macos-rail: "20pt"
  macos-body: "28pt"
components:
  button-primary:
    backgroundColor: "{colors.signal}"
    textColor: "{colors.ink-deep}"
    typography: "{typography.button}"
    rounded: "{rounded.control}"
    padding: "0 20px"
    height: "56px"
  button-primary-hover:
    backgroundColor: "{colors.signal-hover}"
    textColor: "{colors.ink-deep}"
    rounded: "{rounded.control}"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.white}"
    typography: "{typography.button}"
    rounded: "{rounded.control}"
    padding: "0 20px"
    height: "56px"
  mic:
    backgroundColor: "{colors.signal}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    size: "68px"
  mic-listening:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.ink}"
    rounded: "{rounded.full}"
    size: "68px"
  mode-live:
    backgroundColor: "{colors.signal}"
    textColor: "{colors.ink-deep}"
    typography: "{typography.label}"
    rounded: "{rounded.inset-control}"
    padding: "0 12px"
    height: "46px"
  mode-accurate:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.ink-deep}"
    typography: "{typography.label}"
    rounded: "{rounded.inset-control}"
    padding: "0 12px"
    height: "46px"
  dictation-field:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.white}"
    rounded: "{rounded.panel}"
    padding: "25px 92px 30px 28px"
---

# Design System: OpenDictate

## Overview

**Creative North Star: "The Live Dictation Instrument"**

The OpenDictate website presents speech as a precise, observable input process rather than an abstract AI effect. Black surfaces, graphite layers, compact instrument labels, a live caret, and the waveform demo make the interface feel focused and calm. A white microphone is the app mark and the primary action. Silver distinguishes alternate states.

The frontmatter and the following instrument rules describe the public website. Android settings follow the scoped override under Components and the surface brief at `.impeccable/surfaces/android-settings.md`. Settings use native system typography and offer a saved System/Light/Dark appearance choice. The dictation overlay and launcher mark retain their existing identity. Do not mechanically transplant web pixels or fonts into Android.

macOS adapts the same white microphone and graphite identity through SwiftUI/AppKit system colors, system typography, SF Symbols, and native controls. Its dark settings surface is compact and direct; selected controls and keyboard focus retain the macOS accent. Native measurements use points, and the web's display fonts, control dimensions, and hover motion do not apply to this surface.

**Key Characteristics:**

- Black website work surfaces with no light-theme inversion.
- White microphone mark, white primary actions, and a white live caret.
- Restrained silver reserved for alternate modes and state contrast.
- The GitHub Pages site pairs Literata headings with Golos body copy; Android settings use system sans-serif roles.
- Hairline ledgers and routes instead of floating marketing cards.
- Direct, literal diagrams and interactions instead of decorative product mockups.
- System typography and native controls on macOS, with English/Russian copy and visible keyboard focus.

## Colors

The palette is a dark technical field with luminous, deliberately scarce signals.

### Primary

- **Signal White:** The main action, live state, caret, waveform, active status, and positive emphasis.
- **Soft White:** A quieter companion for dense surfaces.
- **Hover White:** Subtle press feedback for filled controls.

### Secondary

- **Mode Silver:** Accurate-mode contrast, replay/listening state, and focus outlines. It is a state distinction, not a second general-purpose brand fill.

### Neutral

- **Working Ink:** The default page canvas.
- **Deep Ink:** The deepest chrome and the text/icon color on luminous fills.
- **Instrument Panel:** Bounded fields and subtle contained surfaces.
- **Raised Panel:** The stronger tonal layer for selected or emphasized dark surfaces.
- **Signal White:** Primary text.
- **Interface Fog:** Secondary copy and inactive controls.
- **Hairline Slate:** Dividers, routes, borders, and structural drawing.

### Native macOS

The native settings surface follows a saved System/Light/Dark choice (Dark by default) and uses semantic system roles rather than hard-coded copies of the web palette. The recording HUD keeps its dark appearance. `windowBackgroundColor` supplies the graphite body and recording indicator; `controlBackgroundColor` supplies the sidebar and message footer; `textBackgroundColor` supplies the dictionary editor. Primary and secondary text use SwiftUI's corresponding foreground styles. Sidebar selection uses the current primary foreground at 9% opacity.

The recording indicator uses `Color.red` for its finish control and `Color.white` for the stop glyph. During recording, the menu bar shows a white filled microphone centered on a 20pt `NSColor.systemRed` circle. Recording red is scoped to these recording states.

System accent color remains on selected segmented controls, enabled switches, picker focus, and keyboard focus rings. The review screenshots show the standard blue accent; its value belongs to macOS rather than a new OpenDictate brand color.

**The Contrast Rule.** On the website, white marks the primary action and live activity; silver marks a deliberate alternate state. Use shape and labels as well as shade to distinguish state.

**The Dark-Only Rule.** The public website is authored as a true dark scheme, not as colors awaiting automatic inversion. This rule does not apply to Android settings.

**The Native Controls Rule.** On macOS, keep semantic system colors and native accent/focus states. Do not force white brand fills onto platform controls.

## Typography

**Website display token:** Unbounded (Arial fallback); the shipped GitHub Pages surface uses the Literata override below.

**GitHub Pages display font:** Literata (Georgia fallback). The site uses it for the hero, section headings, and wordmark. Its calmer letterforms give the download page a more considered tone while keeping the black instrument palette and direct copy.

**Body Font:** Golos Text (Segoe UI fallback)
**Label/Mono Font:** SFMono-Regular (Cascadia Code and Roboto Mono fallbacks)

**Character:** Wide, engineered display type creates unmistakable statements; the body face stays warm and readable; mono labels make modes, metadata, models, and routes feel like operating cues. Local Latin and Cyrillic font files are part of the system, so English and Russian retain the same hierarchy.

### Hierarchy

- **Display:** Hero statements only; balanced wrapping, very tight leading, and restrained weight.
- **Headline:** Major section propositions and the closing call to action.
- **Title:** Compact component or rail headings.
- **Body:** Explanations and supporting prose; keep readable line lengths and use fog for secondary copy.
- **Label:** Uppercase metadata, navigation, mode IDs, and technical annotations.

**The Three-Voice Rule.** Display type states the proposition, Golos explains it, and mono labels the instrument. On GitHub Pages, Literata fills the display role in both Latin and Cyrillic. Android settings do not use this three-font system.

### Native macOS

SwiftUI system type carries all interface copy. The native hierarchy uses the scoped heading, navigation, and brand tokens above; selected navigation changes to medium weight. Body, callout, and caption text retain their native semantic styles. Shortcut labels use the system monospaced design. English and Russian share the same hierarchy and wrap supporting copy vertically.

**The Native Type Rule.** Keep macOS in system typography. The website display/body pairing is specific to that surface.

## Layout

The desktop page is centered at a maximum width of 1280px with 24px outer gutters. The hero is an asymmetric two-column work surface: fluid narrative and live field on the left, a 340px action rail on the right. Subsequent content uses ledgers, split diagrams, and full-width hairline boundaries rather than grids of detached cards. Desktop sections generally breathe with 130px vertical padding.

At 1040px and below, the container caps at 900px with 18px gutters, primary navigation disappears in favor of a compact APK control, the hero and privacy split stack, four setup columns become two, and ledger metadata reflows. At 700px and below, gutters become 16px, sections use 92px vertical padding, ledgers and setup steps become one column, the data route rotates from horizontal to vertical, and closing actions become full-width stacked controls. The center guide disappears on small screens.

**The Working-Surface Rule.** Organize information as one continuous instrument panel separated by rules; do not replace the ledger with a generic feature-card mosaic.

### Native macOS

The settings window uses a fixed sidebar (190pt) and a scrollable content column separated by a native divider. The scoped rail/body padding tokens set the inset rhythm; recurring content groups use native vertical stacks and dividers rather than detached cards. Resizing keeps the rail fixed and lets the content scroll, including the longer Russian copy. Window dimensions and the five-page information architecture are recorded in `.impeccable/surfaces/apps-macos.md`.

## Elevation & Depth

The web system uses no box shadows. Depth comes from dark tonal layers, 1px hairlines, contained fields, and occasional overlap such as the microphone crossing the dictation-field edge. Hover elevation is a small vertical translation, never a shadow bloom.

**The Flat-by-Default Rule.** A surface earns separation through tone and structure. Never add ambient card shadows or glass effects.

macOS settings content follows the same flat structure through system graphite layers and dividers. Native window chrome retains the operating system's elevation. The recording indicator is an AppKit panel with its native shadow enabled; this is floating status feedback, not card elevation within the settings content.

## Shapes

The form language mixes gently rounded rectangular controls with a perfect circular microphone and status dots. Fields use the largest recurring corner, primary controls use a medium corner, and segmented-control selections sit inside a slightly larger rounded frame. Hairlines stay square and precise; wave bars use tiny rounded ends.

**The Shape-Hierarchy Rule.** Curves belong to touchable controls and live signal geometry. Content organization remains rectilinear and ruled.

macOS keeps native rounded fields, switches, buttons, and segmented controls. Only the sidebar selection and dictionary editor use the scoped custom corner tokens above. The recording indicator uses a `Capsule` background and a circular finish control. SF Symbols provide the navigation and status geometry. The packaged app icon is a code-drawn white microphone on a near-black rounded square, adapted from the existing mark by `apps/macos/scripts/generate-icon.swift`; its raster sizes and `.icns` are build output.

## Components

### Buttons

- **Primary:** A full-width or content-width signal action, 56px tall, with dark text and a 12px corner.
- **Hover / Active / Focus:** Hover shifts to a slightly softer white and lifts 3px; active settles 1px and may scale to 0.97 where tactile; focus is a 3px silver outline offset by 4px.
- **Secondary:** Transparent with a hairline border; hover raises it 3px and fills with the panel tone.

macOS uses native SwiftUI buttons at the system's default or small control size. Save and setup actions remain plainly labeled; disabled states, destructive actions, and confirmation alerts use platform behavior. The web button dimensions and lift do not apply.

### Native Inputs / Fields

macOS uses rounded-border secure and search fields, native pop-up pickers, switches, and a segmented Live/Accurate control. The dictionary uses a native text editor with the scoped editor corner. Fields retain native selection, keyboard focus, and text editing. Localized Edit menu commands route Undo, Redo, Cut, Copy, Paste, and Select All through the AppKit responder chain.

### Native Speech Language Picker

The Dictation page uses a compact summary button and a SwiftUI/AppKit popover (340pt) for 64 ISO language choices. Keep system typography, semantic surfaces, native accent/focus and checkbox controls. A visible search caption sits above a rounded-border field; rows show the interface-localized name and, when different, the native name. Helper, caption, native-name, count and empty-result text use semantic primary foreground for readable light appearance. This contrast refinement is scoped to the picker.

Search matches English, Russian, native names and language codes. Rows stay alphabetical in the interface language while multiple selections remain open and save immediately. Automatic detection clears selections; the footer shows the selection count and Done. Enter and Escape close the popover. The control is disabled during recording and follows the saved appearance choice. No new visual tokens or raster assets are introduced. Review evidence and the recognition-testing limit are recorded in `.impeccable/surfaces/apps-macos.md`.

### Native Post-processing Settings

A separate section follows transcription settings on both platforms. Its native switch saves immediately and collapses the editor while off. When enabled, it exposes a monospaced model ID (default `gpt-6-luna`), an editable prompt defaulting to punctuation and capitalization correction, instruction append buttons, and Reset prompt/Cancel/Save actions. Append buttons disable while their exact instruction block is present. Reset changes only the prompt draft; Save applies valid changes to the next dictation. Empty prompts skip processing, and incoming synchronization preserves local edits.

Android retains the neutral Material 3 group, one full-row switch semantic target, outlined fields, wrapping action rows, 48 dp targets and keyboard-aware scrolling. macOS retains system controls, semantic surfaces, an 8pt editor corner and instruction buttons that stack in compact widths. Supporting copy identifies the additional OpenAI request. Saved configuration synchronizes through Drive with a nullable prompt rather than uploading bundled default text. Processing applies to ordinary Live/Accurate final text and Accurate retry; voice editing retains its own path. Surface briefs record native review evidence and limits. No tokens or shipping raster assets are added.

### Cards / Containers

- **Dictation Field:** An Instrument Panel surface with a 1px Hairline Slate border, 16px corners, metadata at the top, and a large cumulative transcript below.
- **Information Structures:** Proofs, modes, and setup steps are ledger rows divided by hairlines, not free-floating cards.
- **Shadow Strategy:** None; use tonal contrast and border geometry.

### Navigation

Desktop navigation uses uppercase mono links in Interface Fog, turning signal on hover. At the tablet breakpoint the text navigation disappears and a compact bordered APK action appears; the brand and language switch remain. All keyboard focus uses the shared secondary outline.

macOS navigation uses system type and SF Symbols in a persistent rail. The selected row gains a tonal background and medium type weight; keyboard focus remains a distinct native ring and may appear on a different row from selection. Navigation labels keep sentence case rather than the web's uppercase mono treatment.

### Mode Switch

The two-option control has a Hairline Slate frame, a 13px outer corner, 4px inset, and 46px options. Live selects signal; Accurate selects secondary. `aria-pressed` is the state source, and Left/Right arrows move between options.

### Dictation Signal

The public demo uses a five-bar signal inside its microphone control. It is white at rest and silver while replaying; its bars animate with staggered 760ms alternate pulses, while the caret blinks every 900ms. The Android launcher retains the white microphone mark on black; the settings header uses the active theme foreground. Under reduced motion, the web demo resolves immediately and all CSS motion collapses to 1ms.

### Android Settings Override

Android settings are a modern, minimalist Operate surface. `SettingsTheme.kt` defines static neutral Material 3 light and dark schemes selected by a saved System/Light/Dark preference (System by default). A 48 dp icon button in the top-right header opens a native menu with a checkmark on the selected option. System mode follows `isSystemInDarkTheme()`; system-bar contrast follows the effective appearance. There is no dynamic wallpaper palette. These settings rules override the website tokens above.

| Role | Light | Dark |
| --- | --- | --- |
| Canvas | `#F3F4F6` | `#101114` |
| Group surface | `#FFFFFF` | `#1B1D22` |
| Inset controls | `#EEF0F3` | `#282B32` |
| Primary text | `#202329` | `#F2F3F5` |
| Supporting text | `#626975` | `#A9B0BC` |
| Primary action | `#22252B` | `#F2F3F5` |
| Divider | `#E4E7EC` | `#30343C` |

Use native Material 3 sans-serif typography for headings, labels, and body copy; monospace is reserved for model IDs. A centered single column caps at 680 dp with 20 dp content gutters. Groups have 16 dp corners, 16 dp row padding, and inset 0.5 dp dividers. Separation comes from tone and spacing; surface tint is transparent. Rows grow with text, action rows have a 76 dp minimum, toggle rows an 80 dp minimum, and controls retain at least 48 dp targets. Respect font scaling, status/navigation bars, and keyboard insets rather than importing website breakpoints.

History and app exclusions use the same 680 dp column and default-weight titleLarge headers. History dates, counts and supporting copy use semantic sans-serif roles. Empty history shows guidance without inactive search controls; populated history scrolls search, feedback and entries together so the keyboard and larger text cannot strand results. Errors use Material error roles and localized messages. The header language action is an unfilled text button with the same muted foreground as adjacent actions.

`SettingsChoiceRow` supplies the primary dictation-mode selector: a 12 dp inset track, 9 dp selected shape, primary/onPrimary fill, a checkmark, equal-height options and a 48 dp minimum target. Labels can wrap with font scaling. Under “Dictation button”, one Appearance action row summarizes the visible pixel diameter and localized physical side; Gestures remains a separate action. Appearance opens `DictationButtonAppearanceSheet`, a native scrollable `ModalBottomSheet` in SettingsSurface with a title and close action. A 112 dp preview precedes the continuous 24–96 dp size slider and an outlined Left/Right selectable group. The selected side uses a checkmark and primaryContainer tone; options retain at least 48 dp targets. The position label sits above full-width choices on narrow or large-text layouts, otherwise beside a 192 dp group. Changes save immediately, with explicit autosave copy. Reset restores both 52 dp and Right and disables only when both are already default; Done dismisses. Native insets and dismissal behavior remain, and saveable visibility survives Activity recreation. These sheet dimensions are scoped component details, not new global tokens.

The signature component is a compact readiness disclosure above the settings groups: it exposes API key, microphone, and Accessibility setup on demand and reports active dictation state. Dictation groups mode, recognition model, languages, word replacements, and dictionary. Post-processing follows in a separate group. The Dictation button group also contains transformation, selected-text dictionary actions, and app exclusions. Advanced contains timeout and model refresh; a test field and privacy note finish the page. Appearance, gestures, languages, word replacements, and dictionary open native modal sheets; model choices use native menus and timeout uses a dialog. Toggle the whole row with one switch semantic target; preserve selected-state checks, disabled/loading/error feedback, and keyboard-safe editing. See the surface brief for interaction details and the sidecar’s `extensions.androidSettings` for native theme roles.

The Dictation button group's Gestures action opens a native scrollable `ModalBottomSheet` on SettingsSurface with a headlineSmall title and muted close action. The approved clean-list layout uses three unfilled rows, full-width 0.5 dp outlineVariant dividers and 24 dp content padding. Each row has an 88 dp minimum, 12 dp vertical padding and a 20 dp illustration-to-text gap. The action uses titleMedium with heading semantics; its gesture hint uses bodyMedium in SettingsMuted, separated by 4 dp. Fixed 87 × 54 dp illustration zones and 75 percent tutorial pictures remain. A bodySmall keyboard-recovery note follows after 20 dp. No inset group, Done button or menu-action ledger is present. Directional hints, illustrations and screen-reader descriptions follow the saved physical button side. Native dismissal and saveable visibility preserve the settings scroll and survive recreation.

Android's Synchronization section lists providers in one rounded group. Google Drive is the only provider: a 24 dp color vector logo, its name, and a trailing native switch in an 80 dp minimum full-row target. Keep descriptions and action buttons out of this group; enabling opens Google authorization when needed, disabling disconnects, and errors use a transient toast. Automatic synchronization remains unchanged.

macOS settings offer the same three choices from a native menu button beside the version at the bottom of the sidebar. The saved choice applies across all five pages; Dark preserves the previous default. Light appearance uses semantic AppKit surfaces and foreground colors, including the selected navigation row. The recording HUD retains its dark nonactivating appearance.

### Native Synchronization Source Choice

First-connection source choice extends the existing Operate settings surfaces through a native Material 3 `AlertDialog` on Android and a SwiftUI sheet on macOS. Keep system typography, established appearance choices, semantic foregrounds and native radio states. Cloud is selected initially; each choice explains its replacement consequence, including propagation to other connected devices for Local. One confirm action names the selected result, alongside Cancel.

Android makes each option a full-row radio target in one selectable group, with vertically scrollable explanatory content and native dialog actions. macOS uses a native `radioGroup` picker, wrapping copy and a 480pt sheet; Enter confirms and Escape cancels through default/cancel keyboard actions. These are scoped component details, not new global dimensions or tokens.

Cloud is read before prompting. Only differing known synced settings require a choice; matching or empty cloud settings continue directly. Cancel disconnects while preserving local and cloud settings. A durable pending-connection gate prevents automatic merging before resolution, including after errors or restart; confirmation re-downloads and checks both sources before applying the chosen authority. Explicit choice overrides offline timestamp order. Successful resolution restores ordinary automatic synchronization. Surface briefs and the sidecar record native review evidence and its limits. No new visual world, raster asset or token is introduced.

### Local Word Replacements

Word replacements extend the existing native settings world: a separate Replacements page in the macOS sidebar and a modal bottom sheet from Android's Dictation group. Recognition hints remain in Dictionary. Both surfaces use English/Russian labels, a global switch, and paired recognized-phrase and replacement fields. Add becomes Save during editing, with Cancel to discard the edit. Saved rules show the source above the replacement, with an individual checkbox and labeled edit/delete controls. Use native system focus and control states; retain Android's minimum touch targets.

The macOS page inherits the scrollable settings body and rounded native fields; Android uses a keyboard-aware lazy list, outlined fields, and scrolls back to the editor when a rule is selected. Supporting copy wraps, and rule rows grow with text. Empty state gives a concrete source → replacement example. Blank fields disable saving; rejected entries show localized validation guidance, and storage errors explain that saved data was kept and saving is unavailable. Keep this feedback beside the editor rather than introducing a new visual treatment.

Rules stay on the device and changes apply to the next dictation in Live and Accurate. The footer explains whole-word/phrase matching without case sensitivity and preserves the spelling entered for the replacement. No synchronization control is present; the user deferred synchronization until Google Drive support exists. This is a scoped Operate extension, reviewed as **ship** using native implementation and macOS/Android phone, dark large-text phone, and tablet captures. Native review has no HTML detector pass; see the surface briefs and sidecar for evidence.

### Native Recording Indicator

Settings offers two persisted styles: Microphone and Waveform. The compact Microphone treatment shows `mic.fill` and a red finish circle with a white stop square during preparation and recording. Nominal geometry is a 44 × 22pt capsule, 9pt medium microphone, 14pt finish circle, 4.5pt stop square with a 1pt corner, and 16pt circular hit area. A uniform 1.25 scale produces the current 55 × 27.5pt control. Processing uses a centered native mini `ProgressView` in a nominal 22 × 22pt circle, scaled to 27.5 × 27.5pt. Idle is hidden. No visible text, timer or shortcut hint belongs to either style.

The Waveform style uses a black capsule with a nominal width of 50pt and height of 14 × φ pt (φ is the golden ratio), scaled to 62.5 × 28.32pt. Ten white bars are nominally 1.5pt wide; spacing keeps their combined full-level bounds at 50 / φ × 14pt. Scaled bars range from 2.5pt in silence to 17.5pt at full input. New microphone RMS levels enter on the right, with square-root mapping and 40ms linear interpolation disabled by Reduce Motion. The whole capsule finishes dictation. Both styles use the same 27.5 × 27.5pt processing spinner. Existing installations retain Microphone; the visibility switch applies to either style.

The borderless, nonactivating AppKit panel retains its native shadow and sits centered on the current screen's visible frame, 28pt above its bottom edge. It cannot become key or main. The preparing/recording finish control invokes `model.stop()` without taking keyboard focus; only processing ignores mouse events. Finish label/help and processing accessibility label are localized.

The menu bar always retains a microphone, independently of HUD style. Only recording uses a white `mic.fill` on a 20pt red circle. Its visible silhouette is 14pt high and centered by its ink bounds, compensating for asymmetric transparent SF Symbol padding. Idle, preparation and processing use the template `mic` symbol on the same 20 × 20pt canvas. Processing adds no ellipsis or other title, keeping the status-item width stable. `MenuBarIconTests` verifies state mapping, equal icon sizes, the filled white silhouette, the circular red background, and equal opposite insets in both native appearances at 1×, 2× and 8×.

The committed recording/processing captures at `.impeccable/review/macos-indicator-recording.png` and `.impeccable/review/macos-indicator-processing.png` document rc.1. The rc.4 refinement halves both dimensions and shows the finish control during preparation; production-view raster tests and offscreen native captures verify its geometry. The built SwiftUI/AppKit indicator is the visual authority.

## Do's and Don'ts

### Do:

- **Do** make the waveform, caret, route lines, and state labels explain how speech reaches text.
- **Do** use hairline structure and tonal layers to organize dense information.
- **Do** preserve the bilingual Latin/Cyrillic font setup and responsive English/Russian copy behavior.
- **Do** keep every web control keyboard-visible and honor `prefers-reduced-motion`.
- **Do** follow the Android settings override for system light/dark themes, native typography, and 48 dp minimum targets.
- **Do** retain macOS system type, semantic colors, native controls, and localized responder-chain text commands.
- **Do** keep the macOS recording indicator compact and nonactivating; its finish control must preserve keyboard focus in the user's app.

### Don't:

- **Don't** introduce generic feature-card grids, detached phone mockups, stock AI imagery, gradients, glassmorphism, or ambient shadows.
- **Don't** use secondary as routine decoration or let it compete with signal for the primary action.
- **Don't** use display faces for website paragraphs or replace the chosen website display face with a default system sans.
- **Don't** flatten desktop composition onto mobile; preserve the explicit 1040px and 700px reflows.
- **Don't** copy web pixel values, web fonts, or hover-only affordances directly into native Android UI.
- **Don't** transplant web fonts, control sizes, or decorative motion into the native macOS surface.
- **Don't** commit generated macOS icon rasters or `.icns`; keep the source generator as the brand asset's provenance.
