"""Reproduce the initial desktop extraction from the pinned upstream renderer.

Only writes new desktop source files. The Android reference is never modified.
Subsequent desktop fixes belong in the extracted source, not repeated regeneration.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/com/ailife/wftools2'
out = root / 'watch-renderer/src/main/kotlin/com/ailife/wftools2/ui/editor/components'
out.mkdir(parents=True, exist_ok=True)
if (out / 'WmLayerRender.kt').exists():
    raise SystemExit('Desktop renderer already exists. Refusing to overwrite subsequent migration fixes.')
original = (source / 'ui/editor/components/WmPreview.kt').read_text(encoding='utf-8')
imports = '\n'.join(line for line in original.splitlines() if line.startswith('import '))
imports = '\n'.join(line for line in imports.splitlines() if not any(token in line for token in [
    'import android.', 'import androidx.media3.', 'asAndroidBitmap', 'asAndroidPath',
    'LocalContext', 'LocalView', 'AndroidView', 'coil.', '.R', 'ui.res.stringResource',
    '.hsc.', 'PreviewRecorder', 'WatchfacePreviewCapturer',
]))
imports += '''
import com.ailife.wftools.pc.graphics.*
import com.ailife.wftools.pc.render.*
import com.ailife.wftools.pc.preview.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.Image
'''

def desktop(text):
    text = text.replace('android.graphics.', 'com.ailife.wftools.pc.graphics.')
    text = text.replace('asAndroidPath()', 'asDesktopPath()')
    text = text.replace('val context = LocalContext.current', '')
    text = text.replace('FontFamily(com.ailife.wftools.pc.graphics.Typeface.createFromFile(target))',
                        'desktopFontFamily(com.ailife.wftools.pc.graphics.Typeface.createFromFile(target))')
    text = text.replace('drawContext.canvas.nativeCanvas.saveLayer(null, paint.asFrameworkPaint())',
                        'drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)')
    text = re.sub(r'R\.string\.(\w+)', r'RendererText.\1', text)
    return text

pieces = [original[original.index('private fun wmLayerOutputSignature'):original.index('private fun wmPreviewTapAction')]]
pieces[0] = pieces[0].replace('private fun wmLayerOutputSignature', 'internal fun wmLayerOutputSignature')
pieces.append(original[original.index('private fun osmTile'):original.index('@Composable\nprivate fun WmMapTileLayer')])
pieces.append(original[original.index('@Composable\nprivate fun WmLayerRender'):original.index('@Composable\nprivate fun WmSelectionOverlay')])
pieces[-1] = pieces[-1].replace('private fun WmLayerRender', 'internal fun WmLayerRender')
pieces.append(original[original.index('@Composable\ninternal fun StencilMaskBox'):])
body = desktop('\n'.join(pieces))
(out / 'WmLayerRender.kt').write_text('package com.ailife.wftools2.ui.editor.components\n\n' + imports + '\n' + body, encoding='utf-8')

geom = (source / 'ui/editor/components/WmPreviewGeometry.kt').read_text(encoding='utf-8')
geom = geom[geom.index('internal fun wmEvalFloat'):]
(out / 'WmPreviewGeometry.kt').write_text('package com.ailife.wftools2.ui.editor.components\n\n' + imports + '\n' + desktop(geom), encoding='utf-8')

shader = (source / 'ui/editor/components/WmShaderPostProcess.kt').read_text(encoding='utf-8')
shader = '\n'.join(line for line in shader.splitlines() if not any(x in line for x in ['import com.ailife.wftools2.R','import androidx.compose.ui.res.stringResource']))
shader = shader.replace('import kotlin.math.abs','import com.ailife.wftools.pc.graphics.*\nimport kotlin.math.abs')
(out / 'WmShaderPostProcess.kt').write_text(desktop(shader), encoding='utf-8')

keys = sorted(set(re.findall(r'RendererText\.(\w+)', body + desktop(geom) + desktop(shader))))
strings = {e.attrib['name']: ''.join(e.itertext()) for e in ET.parse(root/'app/src/main/res/values/strings.xml').getroot() if e.tag == 'string'}
def ktstring(s):
    return '"' + s.replace('\\','\\\\').replace('"','\\"').replace('$','\\$').replace('\n','\\n') + '"'
(out / 'RendererText.kt').write_text('package com.ailife.wftools2.ui.editor.components\n\ninternal fun stringResource(text: String) = text\ninternal object RendererText {\n' + '\n'.join('    const val '+k+' = '+ktstring(strings.get(k,k)) for k in keys)+'\n}\n',encoding='utf-8')
utils = root / 'watch-renderer/src/main/kotlin/com/ailife/wftools2/ui/editor/utils'
utils.mkdir(parents=True,exist_ok=True)
(utils/'LunarUtils.kt').write_text((source/'ui/editor/utils/LunarUtils.kt').read_text(encoding='utf-8'),encoding='utf-8')
print('Extracted renderer, geometry, shaders, strings and lunar utilities without altering Android reference.')
