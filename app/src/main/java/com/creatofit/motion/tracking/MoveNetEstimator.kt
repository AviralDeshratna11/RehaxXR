package com.creatofit.motion.tracking

import android.content.Context
import android.graphics.*
import com.creatofit.motion.core.*
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Analyzer-thread-only inference, including GPU creation and teardown. No frame backlog. */
class MoveNetEstimator(context:Context,val useGpu:Boolean=false, val poseModel:PoseModel=PoseModel.MOVENET_THUNDER):AutoCloseable {
    private val model=context.assets.open(poseModel.asset).use{it.readBytes()}
        .let{ByteBuffer.allocateDirect(it.size).order(ByteOrder.nativeOrder()).apply{put(it);rewind()}}
    private var gpuDelegate:GpuDelegate?=null
    private val interpreter=run {
        val options=Interpreter.Options().setNumThreads(4)
        try {
            if(useGpu){gpuDelegate=GpuDelegate();options.addDelegate(gpuDelegate!!)}
            Interpreter(model,options)
        }catch(error:Exception){gpuDelegate?.close();gpuDelegate=null;throw error}
    }
    private val size=poseModel.inputSize
    private val crops=PoseCropTracker()
    private val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
    private val canvas=Canvas(bitmap)
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels=IntArray(size*size)
    private val input=ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
    private val output=Array(1){Array(1){Array(17){FloatArray(3)}}}
    init {
        check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1,size,size,3))){"Unexpected MoveNet input shape"}
        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1,1,17,3))){"Unexpected MoveNet output shape"}
        check(interpreter.getInputTensor(0).dataType()==DataType.UINT8){"MoveNet v4 requires uint8 RGB input"}
    }
    fun detect(source:Bitmap,id:Long,capturedMs:Long):PoseFrame? {
        val crop=crops.region(source.width,source.height,capturedMs)
        canvas.drawColor(Color.BLACK)
        val scale=size/crop.size
        val transform=Matrix().apply{setScale(scale,scale);postTranslate(-crop.left*scale,-crop.top*scale)}
        canvas.drawBitmap(source,transform,paint)
        bitmap.getPixels(pixels,0,size,0,0,size,size)
        input.rewind()
        pixels.forEach{color->input.put(Color.red(color).toByte());input.put(Color.green(color).toByte());input.put(Color.blue(color).toByte())}
        input.rewind()
        interpreter.run(input,output)
        val pose=MoveNetAdapter.decode(output[0][0],id,capturedMs,android.os.SystemClock.uptimeMillis(),source.width,source.height,crop)
        crops.update(pose,source.width,source.height,capturedMs)
        return pose
    }
    override fun close(){interpreter.close();gpuDelegate?.close();gpuDelegate=null;bitmap.recycle()}
}
