# WFTools-PC design contract

Approved source: user implementation request, 2026-09-11. Product application, precision mouse/keyboard editing at a desktop. No new visual exploration is required: the user explicitly chose the existing mobile Material 3 style and the responsive composition below. Raster probes and north-star mocks are skipped because this is a specified style migration, not an open visual direction. Validate the actual Compose desktop windows instead.

## Color and typography
Use upstream Material 3 palette: light primary #6650A4, secondary #625B71, tertiary #7D5260; dark primary #D0BCFF, secondary #CCC2DC, tertiary #EFB8C8. Preserve Material surface, outline and semantic state roles. Default follows Windows theme with manual light/dark overrides. UI font Segoe UI with Microsoft YaHei UI fallback; watch fonts always come from the archive. Body 14sp, supporting labels 12sp, row/input height 32dp. Icon visual 18dp with 28dp hit area. Preserve Material rounded control vocabulary.

## Composition
System title bar and menu, compact tool strip, central device preview. At >=1100dp use resizable layers/assets, preview, properties columns. At 800–1099dp use preview plus one tabbed inspector. At 720–799dp use preview above an adjustable tabbed inspector. Minimum client area 720x480dp. Controls keep their size; structural collapse handles narrow windows. Independent panel scroll. Initial placement clamped to monitor work area. Store window and divider preferences.

## Interaction
Stable selection IDs; normal single click, Ctrl toggle, Shift visible range, Ctrl+Shift additive range. Right-click preserves an existing multi-selection. Canvas arrow nudge 1 design unit, Shift 10, Alt 0.1. Mouse anchored zoom, middle/Space pan, optional snapping. Input focus owns text commands. Separate edit and interactive preview modes. One command per drag or batch import.

## Device viewport
480x480, 320x386, 368x448, 410x502 and custom 64–2048 integer dimensions. Follow-document, round, rectangle masks. Flue FILL default; FIT/FILL/VERTICAL/HORIZONTAL/STRETCH/CUSTOM with runtime zoom/offset. Document design extent, device viewport and workspace zoom/DPI are distinct. Preview preferences never dirty the document or enter exported data.

## States and motion
All controls have hover, focus, selected, disabled and error states. Inline actionable errors. Empty state offers Open and file drop. Closing dirty documents offers Save/Discard/Cancel. Limit interface transitions to short state feedback; no decorative entrance animations. Keep responsive transitions stable while resizing.
