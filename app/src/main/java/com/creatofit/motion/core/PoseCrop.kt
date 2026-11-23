package com.creatofit.motion.core

import kotlin.math.*

/** Pixel-space square, including padding outside the camera frame. */
data class PoseCrop(val left:Float,val top:Float,val size:Float) {
    fun imagePoint(y:Float,x:Float,width:Int,height:Int)=V3((left+x*size)/width,(top+y*size)/height,0f)
    companion object {
        fun full(width:Int,height:Int):PoseCrop {
            val size=max(width,height).toFloat()
            return PoseCrop((width-size)/2,(height-size)/2,size)
        }
    }
}

/** Previous-frame ROI; expand immediately, contract gradually, reacquire on lost torso. */
class PoseCropTracker {
    private var current:PoseCrop?=null
    private var width=0
    private var height=0
    private var timestamp=-1L
    private var searchedAt=-1L
    private var missingLimbs=false
    fun region(w:Int,h:Int,time:Long):PoseCrop {
        if(w!=width || h!=height || timestamp<0 || time-timestamp>500 || missingLimbs && time-searchedAt>=1500){
            current=PoseCrop.full(w,h);width=w;height=h
            searchedAt=time
        }
        return current?:PoseCrop.full(w,h)
    }
    fun update(frame:PoseFrame?,w:Int,h:Int,time:Long) {
        timestamp=time
        val full=PoseCrop.full(w,h)
        fun good(i:Int)=frame!=null && frame.image[i].confidence>=.4f
        missingLimbs=listOf(13,14,15,16,25,26,27,28).any{!good(it)}
        if(frame==null || !(good(11)||good(12)) || !good(23) || !good(24)){
            current=full;return
        }
        val points=frame.image
        val cx=(points[23].p.x+points[24].p.x)*w/2
        val cy=(points[23].p.y+points[24].p.y)*h/2
        var half=0f
        for(i in MoveNetAdapter.landmarkIds)if(good(i)){
            val margin=if(i in listOf(11,12,23,24))1.9f else 1.25f
            half=max(half,max(abs(points[i].p.x*w-cx),abs(points[i].p.y*h-cy))*margin)
        }
        // Allow space for an unseen distal limb; never zoom into a tiny torso.
        half=max(half,min(w,h)*.25f)
        if(half*2>=full.size || !half.isFinite()){current=full;return}
        val prior=current?:full
        val size=if(half*2>prior.size)half*2 else prior.size+(half*2-prior.size)*.15f
        current=PoseCrop(cx-size/2,cy-size/2,size)
    }
}
