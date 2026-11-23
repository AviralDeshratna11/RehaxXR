package com.creatofit.motion.core

import org.junit.Assert.*
import org.junit.Test

class TrackingArchitectureTest {
    private fun frame(time:Long,x:Float=.5f,confidence:Float=.9f)=PoseFrame(time,time,time,
        List(33){Landmark(V3(x,.5f,0f),confidence)},
        List(33){Landmark(V3(x,.5f,0f),confidence)},640,480)

    @Test fun cropCoordinatesRoundTripIncludingPadding(){
        for((w,h) in listOf(640 to 480,480 to 640)){
            for(crop in listOf(PoseCrop.full(w,h),PoseCrop(-50f,80f,400f))){
                val point=crop.imagePoint(.3f,.7f,w,h)
                assertEquals(.7f,(point.x*w-crop.left)/crop.size,.00001f)
                assertEquals(.3f,(point.y*h-crop.top)/crop.size,.00001f)
            }
        }
    }
    @Test fun cropZoomsThenRecoversOnLossTimeoutOrRotation(){
        val tracker=PoseCropTracker()
        assertEquals(640f,tracker.region(640,480,100).size,0f)
        repeat(15){tracker.update(frame(100+it.toLong()),640,480,100+it.toLong())}
        assertTrue(tracker.region(640,480,120).size<400)
        tracker.update(null,640,480,130)
        assertEquals(PoseCrop.full(640,480),tracker.region(640,480,140))
        tracker.update(frame(150),640,480,150)
        assertEquals(PoseCrop.full(640,480),tracker.region(640,480,700))
        assertEquals(PoseCrop.full(480,640),tracker.region(480,640,710))
    }
    @Test fun adapterMapsCroppedOutputBackToCameraCoordinates(){
        val crop=PoseCrop(100f,50f,300f)
        val result=MoveNetAdapter.decode(Array(17){floatArrayOf(.5f,.5f,.9f)},1,100,110,640,480,crop)!!
        assertEquals(250f/640,result.image[11].p.x,.00001f)
        assertEquals(200f/480,result.image[11].p.y,.00001f)
    }
    @Test fun firstObservationAndReacquiredJointSeedAtMeasurement(){
        val filter=PoseFilter()
        assertFalse(filter.apply(frame(100,confidence=0f))!!.observedJoints!![15])
        // A newly visible joint is seeded at its measurement, not dragged from a stale value.
        val acquired=filter.apply(frame(200,.8f))!!
        assertTrue(acquired.observedJoints!![15])
        assertEquals(.8f,acquired.image[15].p.x,0f)
        filter.apply(frame(700,confidence=0f))
        filter.apply(frame(800,.2f))
        val recovered=filter.apply(frame(950,.2f))!!
        assertEquals(.2f,recovered.image[15].p.x,0f)
        assertTrue(recovered.observedJoints!![15])
    }
    @Test fun stationaryNoiseIsReducedWithoutLargeMotionLag(){
        val filter=PoseFilter()
        filter.apply(frame(100))
        var squared=0f
        for(i in 1..60){
            val result=filter.apply(frame(100+i*33L,.5f+if(i%2==0).01f else -.01f))!!
            squared+=(result.image[15].p.x-.5f)*(result.image[15].p.x-.5f)
        }
        assertTrue(squared/60<.00004f)
        var result=frame(2080)
        for(i in 1..15)result=filter.apply(frame(2080+i*33L,.5f+i*.01f))!!
        assertTrue(.65f-result.image[15].p.x<.05f)
    }
    @Test fun longGapResetsWithoutDraggingOldSubjectIntoNewPose(){
        val filter=PoseFilter();filter.apply(frame(100,.2f))
        val result=filter.apply(frame(2000,.8f))!!
        assertEquals(.8f,result.image[15].p.x,0f)
    }
    @Test fun lightningNeverClaimsWorldDepth(){
        assertFalse(PoseModel.MOVENET_LIGHTNING.hasWorldLandmarks)
        assertEquals(192,PoseModel.MOVENET_LIGHTNING.inputSize)
        assertEquals(256,PoseModel.MOVENET_THUNDER.inputSize)
    }
    @Test fun missingDistalLimbsTriggerPeriodicFullFrameSearch(){
        val tracker=PoseCropTracker();tracker.region(640,480,100)
        val points=frame(200).image.toMutableList()
        points[15]=points[15].copy(confidence=0f)
        val partial=frame(200).copy(image=points)
        tracker.update(partial,640,480,100)
        for(t in 200L..1500L step 100){
            tracker.region(640,480,t);tracker.update(partial,640,480,t)
        }
        assertTrue(tracker.region(640,480,1550).size<400)
        assertEquals(PoseCrop.full(640,480),tracker.region(640,480,1600))
    }
    @Test fun invalidObservationCannotPoisonVelocityOnNextFrame(){
        val filter=PoseFilter();filter.apply(frame(100))
        val bad=frame(133).image.toMutableList()
        bad[15]=Landmark(V3(Float.NaN,.5f,0f),0f)
        filter.apply(frame(133).copy(image=bad))
        val result=filter.apply(frame(166,.52f))!!
        assertTrue(result.image[15].p.x.isFinite())
        assertTrue(result.observedJoints!![15])
    }
}
