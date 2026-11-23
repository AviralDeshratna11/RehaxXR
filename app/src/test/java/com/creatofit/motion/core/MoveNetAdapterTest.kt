package com.creatofit.motion.core

import org.junit.Assert.*
import org.junit.Test

class MoveNetAdapterTest {
    private fun output(width:Int=640,height:Int=480,score:Float=.8f):Array<FloatArray>{
        val sample=PoseSynth.frame(PoseSynth.lateralRaise(),100,width,height).frame
        val box=PoseLetterbox(width,height)
        return Array(17){i->val p=sample.image[MoveNetAdapter.landmarkIds[i]].p
            floatArrayOf((p.y*height*box.scale+box.top)/256,(p.x*width*box.scale+box.left)/256,score)}
    }
    @Test fun letterboxInvertsPortraitAndLandscapeWithoutStretching(){
        for((width,height) in listOf(640 to 480,480 to 640)){
            val frame=MoveNetAdapter.decode(output(width,height),1,100,120,width,height)!!
            val expected=PoseSynth.frame(PoseSynth.lateralRaise(),100,width,height).frame
            for(i in MoveNetAdapter.landmarkIds){
                assertEquals(expected.image[i].p.x,frame.image[i].p.x,.0001f)
                assertEquals(expected.image[i].p.y,frame.image[i].p.y,.0001f)
            }
            assertFalse(frame.hasWorldLandmarks)
            assertTrue(frame.world.all{it.p.z==0f})
            assertEquals(0f,frame.image[31].confidence,0f)
        }
    }
    @Test fun badAndEmptyDetectionsDoNotCreateAPose(){
        assertNull(MoveNetAdapter.decode(Array(17){floatArrayOf(.5f,.5f,.01f)},1,100,120,640,480))
        assertNull(MoveNetAdapter.decode(Array(16){floatArrayOf(.5f,.5f,1f)},1,100,120,640,480))
        val data=output();data.forEach{it[0]=Float.NaN}
        assertNull(MoveNetAdapter.decode(data,1,100,120,640,480))
    }
    @Test fun paddingKeypointsAreNotConfidentJoints(){
        val data=output(480,640);data[9]=floatArrayOf(.5f,0f,.99f)
        val frame=MoveNetAdapter.decode(data,1,100,120,480,640)!!
        assertEquals(0f,frame.image[15].confidence,0f)
    }
    @Test fun twoDimensionalMarkersCannotBeExportedAsWorldCoordinates(){
        val frame=MoveNetAdapter.decode(output(),1,100,120,640,480)!!
        val capture=MotionCapture();capture.start();capture.add(frame)
        assertFalse(capture.recording);assertNull(capture.finish())
    }
    @Test fun twoDimensionalMoveNetJointsDriveTheMuscleEngine(){
        val filter=PoseFilter();val solver=BodySolver();val bio=Biomechanics();val engine=MuscleEngine()
        var analysis=MuscleAnalysis.EMPTY
        for(time in 100L..1800L step 33){
            val frame=MoveNetAdapter.decode(output(score=.50f),time,time,time+20,640,480)!!
            val filtered=filter.apply(frame)!!
            assertEquals(.50f,filtered.image[11].confidence,0f)
            val body=solver.solve(filtered)!!
            analysis=engine.update(body,bio.analyze(body))
        }
        assertTrue(analysis.entries.any{it.muscle==Muscle.LATERAL_DELTOID && it.role==MuscleRole.PRIME})
    }
}
