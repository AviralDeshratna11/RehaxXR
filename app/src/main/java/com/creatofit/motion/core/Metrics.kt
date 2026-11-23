package com.creatofit.motion.core

import java.util.ArrayDeque

data class MetricsSnapshot(val cameraFps:Float,val poseFps:Float,val renderFps:Float,
    val inferenceMedian:Float,val poseAgeMedian:Float,val poseAgeP95:Float,val analysisDropped:Long,
    val captured:Long,val previewDeltaMs:Long,val displayedPoseAgeMs:Long)
class Metrics {
    private val camera=ArrayDeque<Long>(); private val pose=ArrayDeque<Long>(); private val render=ArrayDeque<Long>()
    private val duration=ArrayDeque<Long>(); private val age=ArrayDeque<Long>()
    private var captured=0L; private var dropped=0L
    private var lastRenderedId=-1L
    private var latestCaptureMs=0L; private var displayPoseMs=0L
    @Synchronized fun camera(now:Long){tick(camera,now)}
    @Synchronized fun analyzed(now:Long){captured++;latestCaptureMs=now}
    @Synchronized fun drop(){dropped++}
    @Synchronized fun pose(now:Long,submitted:Long){tick(pose,now); sample(duration,now-submitted)}
    @Synchronized fun render(now:Long,frame:PoseFrame?) {
        tick(render,now)
        if(frame!=null) {
            displayPoseMs=frame.capturedMs
            if(frame.id!=lastRenderedId){sample(age,now-frame.capturedMs);lastRenderedId=frame.id}
        }
    }
    private fun tick(q:ArrayDeque<Long>,now:Long){q.add(now);while(q.isNotEmpty() && now-q.first>2000)q.removeFirst()}
    private fun sample(q:ArrayDeque<Long>,v:Long){q.add(v.coerceAtLeast(0));if(q.size>600)q.removeFirst()}
    private fun fps(q:ArrayDeque<Long>,now:Long):Float {
        if(q.size<2 || now-q.last>1500) return 0f
        return (q.size-1)*1000f/(q.last-q.first).coerceAtLeast(1)
    }
    private fun percentile(q:ArrayDeque<Long>,p:Float):Float {
        if(q.isEmpty())return 0f
        val s=q.sorted();return s[((s.size-1)*p).toInt()].toFloat()
    }
    @Synchronized fun snapshot(now:Long)=MetricsSnapshot(fps(camera,now),fps(pose,now),fps(render,now),
        percentile(duration,.5f),percentile(age,.5f),percentile(age,.95f),dropped,captured,
        if(displayPoseMs>0)(latestCaptureMs-displayPoseMs).coerceAtLeast(0) else 0,
        if(displayPoseMs>0)now-displayPoseMs else 0)
}
