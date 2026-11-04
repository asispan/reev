# Reev colour palette

This is Reev's brand palette, approved by Asis on 4 Nov 2026. Use it for everything Reev-related: the website, the app UI, store assets, Figma, decks and social posts. Don't introduce new hues; derive tints from these.

## Core

| Token | Hex | Use |
|---|---|---|
| `ivory` (bg) | `#FAF8F4` | Page / canvas background |
| `wall` | `#F2EFE9` | Chips, insets, secondary surfaces |
| `ink` | `#121826` | Primary text, dark buttons |
| `ink-2` | `#475066` | Body text |
| `muted` | `#8B8F9C` | Captions, meta |
| `hair` | `rgba(18,24,38,.10)` | Hairlines, card borders |
| `rani` | `#E2336B` | Primary accent (rani pink): highlights, dots, links-on-hover |
| `rani-soft` | `#FF9DB8` | Glows, tints |
| `peacock` | `#0E8C8C` | Secondary accent (peacock teal): outgoing chat, ticks |
| `teal-soft` | `#8FE0D6` | Glows, chips on dark |
| `deep-teal` | `#0B6E70` | Eyebrows / kickers |
| `plum` | `#8E2E7C` | Gradient mid-tone |
| `night` | `#06171A` | Dark sections |
| dark button | `#1F3A40 → #0C1C20` | Primary pill buttons |

## Signature gradient ("large card")

Used for the logo and the hero download card.

```
#9DF0E4 → #2FB3A8 → #2B5E8C → #8E2E7C → #E2336B  (logo ends #FF7FA3)
CSS: radial-gradient(90% 120% at 15% 0%, #9DF0E4 0%, #2FB3A8 30%, #2B5E8C 58%, #8E2E7C 82%, #E2336B 100%)
```

## Light fields (Turrell-style glows)

- Hero glow: a `rani` core with `#5FD0C4` teal wings, blurred about 40px.
- Light rectangle behind the phone: `#D8FFF8 → #8FE0D6 → #2FB3A8 → #0E8C8C`.
- Night oculus: `#FFD6E2 → #FF8FB0 → #B83A8C → #2B4E7A → #08262B`.

## Type (web)

- Logo: Tanker, drawn as an inflated matte "bubble" with the signature gradient.
- Everything else: Ranade Light (300) and Excon Medium (500), from Fontshare.
