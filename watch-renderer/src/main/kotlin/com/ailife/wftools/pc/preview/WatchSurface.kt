package com.ailife.wftools.pc.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ailife.wftools.pc.render.*
import com.ailife.wftools2.domain.editor.watchmaker.*
import com.ailife.wftools2.ui.editor.components.WmLayerRender
import kotlin.math.min

/** The same composable surface is used by the editor and offscreen thumbnail export. */
@Composable fun WatchSurface(
    project:WmWatchfaceProject,render:RenderSession,device:DevicePreviewSettings,
    modifier:Modifier=Modifier,layerOverride:(Int,WmWatchfaceLayer)->WmWatchfaceLayer={_,layer->layer},
) {
    val design=remember(project.rootAttrs){DesignExtent.fromAttributes(project.rootAttrs)}
    val viewport=remember(design,device){ViewportTransform.create(design,device)}
    val round=device.shape.isRound(project.shape)
    val mask=remember(round){GenericShape{size,_->
        if(round){val r=min(size.width,size.height)/2;addOval(Rect(size.width/2-r,size.height/2-r,size.width/2+r,size.height/2+r))}
        else addRect(Rect(0f,0f,size.width,size.height))
    }}
    val fast=rememberUpdatedState(render.frame)
    val fixedNow=remember(render){render.frame.now}
    CompositionLocalProvider(LocalDensity provides Density(1f),LocalMediaPlayback provides render.media,LocalPreviewRunning provides(render.playing && render.visible)) {
        Box(modifier.wrapContentSize(Alignment.TopStart,unbounded=true).requiredSize(device.width.dp,device.height.dp).clip(mask).background(Color.Black)) {
            Box(Modifier.wrapContentSize(Alignment.TopStart,unbounded=true).requiredSize(design.width.toFloat().dp,design.height.toFloat().dp).graphicsLayer {
                transformOrigin=TransformOrigin(0f,0f);translationX=viewport.left.toFloat();translationY=viewport.top.toFloat();scaleX=viewport.scaleX.toFloat();scaleY=viewport.scaleY.toFloat()
            }.clip(mask).background(runCatching {Color(com.ailife.wftools.pc.graphics.Color.parseColor("#"+render.evaluate(project.rootAttrs["bg_color"] ?: project.background).removePrefix("#")))}.getOrDefault(Color.Black))) {
                Box(Modifier.requiredSize(512.dp).graphicsLayer{translationX=(design.width/2-256).toFloat();translationY=(design.height/2-256).toFloat()}) {
                    project.wmLayers.forEachIndexed{index,original->key(index){
                        WmLayerRender(layerOverride(index,original),render.tags,render.variables,fast,fixedNow,render.directory,Density(1f),render.isBright,render.script)
                    }}
                }
            }
        }
    }
}
