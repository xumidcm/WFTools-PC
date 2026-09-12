# WFTools-PC design contract

Approved source: user implementation request, 2026-09-11. Product application, precision mouse/keyboard editing at a desktop. The first desktop shell was rejected by the user because its generic outlined controls and flat property list did not resemble mobile WFTools. Visual acceptance requires a reviewable, rendered editor showing the mobile component vocabulary below, followed by responsive/DPI checks. Matching palette alone is insufficient.

## 手机版视觉迁移基线（用户反馈后的修正）

- `EditorMainArea.kt`：48dp、轻微 tonal/shadow elevation 的操作栏；文档名、独立语义图标、仅激活状态使用 tonal 按钮。保存是明确的主操作。
- `LayersTab.kt`：圆角图层卡片，真实图片缩略图／形状色块／文字预览；普通 surfaceVariant、单选 primaryContainer、多选 secondaryContainer。桌面压紧卡片，不退化成圆点加一行原始类型。
- `WmParamCard`：ElevatedCard、12dp 内边距、粗体标题。按内容、位置、外观等语义组织，避免裸露的键值表。
- `LabeledSlider`：标签、直接编辑值、`fx`、减号／滑杆／加号。桌面数值输入保持 32dp；密集的 X/Y、宽高可以成对排列。表达式不得因滑杆操作被静默替换成采样值。
- `WmColorRow`／`WmAlignmentRow`：真实色块与调色器、九宫格锚点；显隐、形状等使用中文语义选择控件。
- `PreviewTab.kt`：亮屏／息屏与速度的 segmented controls，播放图标，中文分类的可折叠模拟设置。完整原始字段放入高级区。
- 导航沿用 Material 图标和选中指示。不要把所有按钮、标签和属性统一成描边矩形。

先检查 1280×800 的真实编辑界面，再检查窄窗口与 DPI 矩阵。已有控制器、编辑命令、稳定 ID 和读写保证不因视觉重构退化。

## Color and typography
Use upstream Material 3 palette: light primary #6650A4, secondary #625B71, tertiary #7D5260; dark primary #D0BCFF, secondary #CCC2DC, tertiary #EFB8C8. Preserve Material surface, outline and semantic state roles. Default follows Windows theme with manual light/dark overrides. UI font Segoe UI with Microsoft YaHei UI fallback; watch fonts always come from the archive. Body 14sp, supporting labels 12sp. The user's latest correction prioritizes comfortable spacing: inputs around 38dp, layer cards around 58dp with a 38–40dp thumbnail, 12–16dp group padding. Preserve Material rounded controls, sliders, and distinct semantic widgets rather than enforcing the original dense 32dp target.

Layer thumbnails follow Flue's current `renderTextLayerThumbnail` approach: use the runtime's actual expression result, font/BMFont, color and outline; remove document position/rotation/scale; render in a transparent 512px working area, find alpha bounds and fit the result to the tile. Key by stable layer ID and source attributes, so reordering never shows another layer's preview. Image/animation tiles show the source asset/first frame; video tiles show a decoded frame. Thumbnail work runs away from the UI event thread. Contrast backgrounds preserve both dark and light authored glyphs without tinting them.

## Composition
Use a theme-aware Windows caption with the WFTools icon, then one Material 3 application bar inside the client area. Do not show the classic AWT menu strip. Keep the document title, undo, redo, action-test toggle and save action visible; place file, edit, layer, appearance, snapping and secondary commands in the top-right overflow menu. At >=1100dp use resizable layers/assets, preview, properties columns. At 800–1099dp use preview plus one tabbed inspector. At 720–799dp use preview above an adjustable tabbed inspector. Minimum client area 720x480dp. Controls keep their size; structural collapse handles narrow windows. Independent panel scroll. Initial placement clamped to monitor work area. Store window and divider preferences.

The launcher follows the mobile project's welcoming hierarchy: recent watch faces and a prominent resume-last-session action occupy the main area; draft recovery stays visible; developer and upstream attribution form a quiet footer. The developer portrait is the supplied Arona image, clipped to a circle rather than restyled.

## Interaction
Stable selection IDs; normal single click, Ctrl toggle, Shift visible range, Ctrl+Shift additive range. Right-click preserves an existing multi-selection and opens a Material 3 menu. Canvas arrow nudge 1 design unit, Shift 10, Alt 0.1. A plain wheel over a layer resizes that layer; Ctrl+wheel zooms the workspace around the pointer. Ctrl+left drag or middle drag pans the workspace. In direct-drag mode the pressed layer becomes the drag target immediately; blank space never moves the old selection. In select-then-drag mode a click chooses first and the current selection can be dragged from the canvas. Input focus owns text commands. The top-right hand button controls interactive action testing; automatic snapping remains a separate overflow-menu setting. One command per drag or batch import.

Live property and canvas transforms render from a transient attribute layer. On release, that transient state remains installed until the new persistent revision is visible, so the rendered layer, selection outline and final XML never expose an old intermediate frame. Layer-list reorder gestures are owned by the list viewport and capture stable layer IDs; edge auto-scroll and wheel scrolling cannot dispose the active drag or change its target.

## Device viewport
The editing coordinate space is always 512×512. New documents and other new-canvas entry points default to square. The visible mask comes from the document's saved `shape`: opening a round watch shows a round canvas, changing the shape in the Project panel redraws immediately and marks the document for saving. Device simulation is off by default. When enabled it offers 480×480, 320×386, 368×448, 410×502 and custom 64–2048 dimensions, but it does not provide a second shape override. Resolution controls live in the preview settings panel and never occupy the canvas toolbar. Flue FIT/FILL/VERTICAL/HORIZONTAL/STRETCH/CUSTOM runtime zoom/offset remains available for simulation. Preview dimensions never dirty the document or enter exported data.

## Layer and asset panels
The layer list begins with one full-width pill-shaped add button, followed by search, filters and real thumbnails. Type-specific property cards expose the same known WatchMaker fields as the mobile editor; raw fields remain available for forward compatibility, not as a substitute for ordinary controls.

The asset tab uses real previews for images, animation first frames, video frames and fonts. Dragging an asset card onto the canvas creates a layer at the drop position. External files dropped on the canvas or layer panel are copied to the library and create layers; files dropped on the asset panel, or chosen from its import action, only join the library. The asset context menu can copy a path, reveal a file and remove an unreferenced asset. Referenced assets show which layers use them and refuse destructive removal.

## States and motion
All controls have hover, focus, selected, disabled and error states. Inline actionable errors. Empty state offers Open and file drop. Closing dirty documents offers Save/Discard/Cancel. Limit interface transitions to short state feedback; no decorative entrance animations. Keep responsive transitions stable while resizing.
