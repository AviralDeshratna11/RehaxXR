package com.creatofit.motion.test

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import com.creatofit.motion.tracking.MoveNetEstimator
import com.creatofit.motion.core.PoseModel

/** Device-only native model smoke check; blank images do not benchmark accuracy. */
class MoveNetSmokeInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val result=Bundle()
        val bitmap=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888)
        try {
            for(backend in listOf(PoseModel.MOVENET_THUNDER,PoseModel.MOVENET_LIGHTNING))for(gpu in listOf(false,true)){
                val name=backend.name+if(gpu)"_GPU" else "_CPU"
                try {
                    val times=mutableListOf<Long>()
                    MoveNetEstimator(targetContext,gpu,backend).use{model ->
                        repeat(12){i->
                            val began=SystemClock.uptimeMillis()
                            model.detect(bitmap,i.toLong(),began)
                            times+=SystemClock.uptimeMillis()-began
                        }
                    }
                    val measured=times.drop(2).sorted()
                    result.putLong("${name}_median_ms",measured[measured.size/2])
                }catch(error:Exception){
                    if(!gpu)throw error
                    result.putString("${name}_unavailable",error.message)
                }
            }
            result.putString("stream","PASS: MoveNet Thunder and Lightning native initialization and CPU/GPU inference checks. Blank-image smoke check only.\n")
            result.putString("model","MoveNet Thunder 256 / Lightning 192, float16 v4 / tracked crop")
            finish(Activity.RESULT_OK,result)
        }catch(error:Throwable){
            result.putString("stream","FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED,result)
        }finally{bitmap.recycle()}
    }
}
