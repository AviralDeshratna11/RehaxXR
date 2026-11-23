package com.creatofit.motion.core

import kotlin.math.*

/** Square letterboxing preserves image aspect and includes the complete camera frame. */
data class PoseLetterbox(val width:Int,val height:Int,val inputSize:Int=256) {
    val scale=inputSize.toFloat()/max(width,height)
    val left=(inputSize-width*scale)*.5f
    val top=(inputSize-height*scale)*.5f
    fun imagePoint(y:Float,x:Float)=V3((x*inputSize-left)/scale/width,(y*inputSize-top)/scale/height,0f)
}

object MoveNetAdapter {
    // COCO-17 to the existing MediaPipe joint IDs; unsupported fingers/feet remain unknown.
    val landmarkIds=intArrayOf(0,2,5,7,8,11,12,13,14,15,16,23,24,25,26,27,28)
    fun decode(output:Array<FloatArray>,id:Long,capturedMs:Long,inferredMs:Long,width:Int,height:Int,crop:PoseCrop=PoseCrop.full(width,height)):PoseFrame? {
        if(output.size!=17 || output.any{it.size!=3})return null
        val image=MutableList(33){Landmark(V3(0f,0f,0f),0f)}
        output.forEachIndexed{i,v ->
            if(v.all{it.isFinite()} && v[2] in 0f..1f){
                val point=crop.imagePoint(v[0],v[1],width,height)
                val confidence=if(point.x in .01f.. .99f && point.y in .01f.. .99f)v[2] else 0f
                image[landmarkIds[i]]=Landmark(point,confidence)
            }
        }
        if(image.count{it.confidence>=.30f}<4 || max(image[11].confidence,image[12].confidence)<.30f)return null
        val aspect=width.toFloat()/height
        val hip=(image[23].p+image[24].p)*.5f
        val shoulder=(image[11].p+image[12].p)*.5f
        val torso=V3((shoulder.x-hip.x)*aspect,shoulder.y-hip.y,0f).length()
        val scale=if(torso>.05f).52f/torso else 1f
        // Planar display/angle coordinates ONLY. These are not world coordinates or meters.
        val planar=image.map{it.copy(p=V3((it.p.x-hip.x)*aspect*scale,(it.p.y-hip.y)*scale,0f))}
        return PoseFrame(id,capturedMs,inferredMs,image,planar,width,height,hasWorldLandmarks=false,scoreFloor=.30f)
    }
}
