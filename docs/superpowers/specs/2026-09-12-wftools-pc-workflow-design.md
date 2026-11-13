# WFTools-PC Workflow Follow-up Design

## Scope

This change continues the Windows `.watch` editor workflow. It covers last-session
resume, file actions, `.watch` resource import, canvas gestures, drag-and-drop,
transform-handle cursors, global-script visibility, and save/close actions.

## Confirmed behavior

- The launcher resumes one document: the last active document from the previous
  exit. Recent projects remain manual choices and are not opened as a batch.
- The top-left folder control opens a file-context menu. The toolbar has `保存`
  followed by `另存为`.
- The close confirmation offers `另存为` in addition to save, discard, and cancel.
- Dropping a `.watch` on the launcher opens it. Dropping one on an editor asks
  whether to open it in a new window or import its resources into the current
  document.
- Importing a `.watch` copies image/GIF, video, font, bitmap-font, and audio
  resources into the current project and creates layers for visual resources.
  It does not merge project attributes or the source global script. Audio is
  imported into `sfx/` without inventing a visual layer.
- Dropping ordinary assets on an editor routes them by target: property replacement,
  library-only import, or canvas import plus layer creation. Dropping unsupported
  files on the launcher produces a clear message.
- The middle mouse button pans the canvas. Ctrl+wheel zooms the canvas. When the
  pointer is not over a selectable layer, ordinary wheel input zooms the canvas;
  the existing layer-scale behavior remains available when a selected layer is
  under the pointer.
- Transform handles expose matching resize/rotate/move pointer cursors while
  hovered.
- The project page always exposes the global-script page. If the archive contains
  no script, it shows an empty editor and an explicit empty-state message. Script
  checking compiles only and never runs the script.

## Data flow and boundaries

- `EditorPreferences` stores one last-active document identity/path separately from
  the recent-project list.
- `EditorApplicationModel` updates that value when a document receives focus and
  resumes only that value or its matching recovery draft.
- `DropRegistry` owns editor drop decisions. A `.watch` drop uses a UI confirmation
  before invoking either `app.open` or a core resource-import command.
- `DocumentSession` owns archive extraction, collision-safe resource copying, and
  visual-layer creation for imported resources. Unknown/project/script entries are
  excluded from the import operation.
- `WatchPreview` owns canvas gesture interpretation and hover cursor selection;
  document mutation remains serialized through `DocumentController`.
- `CommandDispatcher` and `DocumentCloseDialog` share the existing save pipeline;
  save-as first commits a valid script draft, then chooses a new path.

## Error handling

- Invalid or mixed drops do not mutate the document and display an actionable error.
- Resource collisions are renamed within the destination asset directory while
  layer paths point to the copied names.
- A failed resource import is reported through the existing document error state
  and does not claim success.
- A cancelled file chooser leaves the document unchanged.
- Save-as preserves the current document when the chooser is cancelled or the
  destination conflicts with another open document.

## Verification

Regression tests cover single-document resume, file menu and save-as actions,
`.watch` import routing, resource import and collision handling, middle-button
pan and blank-canvas wheel zoom, handle cursor mapping, script empty-state access,
and close-dialog save-as routing. The full core, renderer, and desktop test suites
plus `git diff --check` are required before completion.
