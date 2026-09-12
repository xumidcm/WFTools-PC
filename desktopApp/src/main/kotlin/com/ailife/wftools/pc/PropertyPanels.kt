package com.ailife.wftools.pc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Public inspector entry points. The sizeable parity implementations live in focused files. */
@Composable fun PropertiesPanel(doc: DocumentController, drops: DropRegistry, modifier: Modifier, wideEditor: Boolean = false) =
    KnownPropertiesPanel(doc, drops, modifier, wideEditor)

@Composable fun ProjectPanel(doc: DocumentController, modifier: Modifier) =
    KnownProjectPanel(doc, modifier)

@Composable fun PreviewSettingsPanel(doc: DocumentController, modifier: Modifier) =
    KnownPreviewSettingsPanel(doc, modifier)
