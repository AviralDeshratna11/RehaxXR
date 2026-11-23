package com.creatofit.motion.core

import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, bounded marker recording. Camera images are never retained. */
class MotionCapture {
    private val frames=ArrayList<PoseFrame>()
    @Volatile var recording=false; private set
    @Synchronized fun start(){frames.clear();recording=true}
    @Synchronized fun cancel(){recording=false;frames.clear()}
    @Synchronized fun stop(){recording=false}
    @Synchronized fun add(frame:PoseFrame){
        if(!recording)return
        if(!frame.hasWorldLandmarks){recording=false;frames.clear();return}
        if(frames.lastOrNull()?.capturedMs?.let{frame.capturedMs<=it}==true)return
        if(frames.size>=1800 || frames.firstOrNull()?.let{frame.capturedMs-it.capturedMs>60_000}==true){recording=false;return}
        frames.add(frame)
    }
    @Synchronized fun finish():String? {
        recording=false
        if(frames.size<2){frames.clear();return null}
        val first=frames.first().capturedMs
        val samples=JSONArray()
        frames.forEach{frame ->
            val points=JSONArray()
            frame.world.forEach{l -> points.put(JSONArray(listOf(l.p.x,l.p.y,l.p.z,l.confidence)))}
            samples.put(JSONObject().put("time",(frame.capturedMs-first)/1000.0).put("markers",points))
        }
        frames.clear()
        return JSONObject().put("format","creatofit-markers-v1")
            .put("units","m").put("coordinates","mediapipe-camera-pelvis-relative")
            .put("frames",samples).toString()
    }
}
