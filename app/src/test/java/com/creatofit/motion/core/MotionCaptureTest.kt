package com.creatofit.motion.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MotionCaptureTest {
    private fun frame(time:Long,x:Float=.5f):PoseFrame {
        val points=List(33){Landmark(V3(x,.5f,0f),1f)}
        return PoseFrame(time,time,time,points,points,640,480)
    }
    @Test fun captureIsOptInOrderedAndPreservesTimeAndConfidence(){
        val capture=MotionCapture()
        capture.add(frame(0));assertNull(capture.finish())
        capture.start();capture.add(frame(100));capture.add(frame(100));capture.add(frame(150,.6f))
        val data=JSONObject(capture.finish()!!)
        val frames=data.getJSONArray("frames")
        assertEquals(2,frames.length());assertEquals(.05,frames.getJSONObject(1).getDouble("time"),1e-6)
        assertEquals(33,frames.getJSONObject(0).getJSONArray("markers").length())
        assertFalse(capture.recording);assertNull(capture.finish())
    }
    @Test fun captureStopsAtSixtySecondsAndCancelClearsData(){
        val capture=MotionCapture();capture.start();capture.add(frame(100));capture.add(frame(133))
        capture.add(frame(60_101));assertFalse(capture.recording)
        assertEquals(2,JSONObject(capture.finish()!!).getJSONArray("frames").length())
        capture.start();capture.add(frame(200));capture.add(frame(233));capture.cancel()
        assertNull(capture.finish())
    }
    @Test fun adaptiveFilterSuppressesJitterButFollowsDeliberateMovement(){
        val filter=PoseFilter();filter.apply(frame(100))
        var filteredNoise=0f
        repeat(60){i ->
            val pose=filter.apply(frame(133+i*33L,.5f+if(i%2==0).005f else -.005f))!!
            if(i>=30)filteredNoise+=abs(pose.world[15].p.x-.5f)
        }
        assertTrue(filteredNoise/30<.002f)
        var tracked=.5f
        repeat(30){i ->tracked=filter.apply(frame(2113+i*33L,.5f+(i+1)*.01f))!!.world[15].p.x}
        assertTrue(tracked>.76f)
        filter.reset();assertEquals(.2f,filter.apply(frame(1,.2f))!!.world[15].p.x,0f)
    }
}
