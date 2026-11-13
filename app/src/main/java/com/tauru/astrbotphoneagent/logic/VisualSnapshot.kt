package com.tauru.astrbotphoneagent.logic

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class VisualFrame(val snapshot:UiSnapshot,val imageHash:String,val jpeg:String,val imageWidth:Int,val imageHeight:Int)

internal object VisualTextPolicy {
    private val sensitive=Regex("密码|验证码|支付|付款|转账|购买|下单|发送|发布|删除|卸载|安装|授权|允许|登录|登入|同意|确认|确定|提交|password|verification|payment|checkout|purchase|delete|uninstall|permission|send|publish|sign.?in|log.?in",RegexOption.IGNORE_CASE)
    fun safeLabel(text:String)=text.isNotBlank()&&text.length<=160&&!sensitive.containsMatchIn(text)
    fun sensitivePage(labels:List<String>)=labels.any { Regex("输入密码|验证码|支付密码|确认付款|password|one.time.password|credit.card",RegexOption.IGNORE_CASE).containsMatchIn(it) }
    fun checkAction(kind:String,oldHash:String,newHash:String,ageMs:Long,label:String="") {
        if(kind !in setOf("tap","back"))throw UiFailure("ui_visual_action_unsupported","截图辅助仅支持点击文字控件或返回，请手动处理输入与拖动")
        if(ageMs !in 0..30_000)throw UiFailure("ui_snapshot_expired","截图已过期，请重新观察")
        if(oldHash!=newHash)throw UiFailure("ui_snapshot_changed","截图已变化，请重新观察")
        if(kind=="tap"&&!safeLabel(label))throw UiFailure("ui_sensitive_action","截图目标含敏感或不明确操作，请手动处理")
    }
}

/** Pixels and OCR stay in memory. The model receives nodes; coordinates remain device-owned. */
internal class VisualSnapshotReader {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    fun hash(bitmap:Bitmap):String {
        val bytes=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(bytes)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString(""){"%02x".format(it)}
    }
    fun read(bitmap:Bitmap,packageName:String):VisualFrame {
        val stamp=SystemClock.elapsedRealtime();val hash=hash(bitmap)
        val result=try{Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),8,TimeUnit.SECONDS)}
            catch(interrupted:InterruptedException){Thread.currentThread().interrupt();throw interrupted}
            catch(_:Exception){throw UiFailure("ui_snapshot_unavailable","本机文字识别不可用或超时，请重新观察")}
        val lines=result.textBlocks.flatMap{it.lines}
        if(lines.size>200)throw UiFailure("ui_snapshot_too_large","截图文字过多，请缩小任务范围")
        if(VisualTextPolicy.sensitivePage(lines.map{it.text}))throw UiFailure("ui_sensitive_page","此页面包含敏感输入或支付信息，请手动处理")
        val nodes=lines.mapNotNull { line ->
            val b=line.boundingBox?:return@mapNotNull null;val label=line.text.filter{it.code>=32}.take(160)
            if(label.isBlank()||b.left<0||b.top<0||b.right>bitmap.width||b.bottom>bitmap.height||b.width()<=0||b.height()<=0)return@mapNotNull null
            // System bars are not task controls even when OCR sees their text.
            if(b.top<bitmap.height*0.025 || b.bottom>bitmap.height*0.975)return@mapNotNull null
            UiNode("n0","",label,"","ocr.text",packageName,listOf(b.left,b.top,b.right,b.bottom),
                VisualTextPolicy.safeLabel(label),false,false,false,false,true,false)
        }
        if(nodes.size>160)throw UiFailure("ui_snapshot_too_large","截图可用文字节点过多，请缩小任务范围")
        val numbered=nodes.mapIndexed{index,node->node.copy(id="n$index",resourceId="ocr:$index")}
        if(nodes.isEmpty())throw UiFailure("ui_snapshot_unavailable","截图中未找到可核验文字，请手动接手")
        val longest=maxOf(bitmap.width,bitmap.height);val ratio=minOf(1.0,1280.0/longest)
        val scaled=if(ratio<1)Bitmap.createScaledBitmap(bitmap,(bitmap.width*ratio).toInt(),(bitmap.height*ratio).toInt(),true)else bitmap
        val imageWidth=scaled.width;val imageHeight=scaled.height
        val stream=ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,75,stream)
        if(scaled!==bitmap)scaled.recycle()
        val data=stream.toByteArray();if(data.size>900_000)throw UiFailure("too_large","截图过大，无法用于辅助观察")
        val snapshot=UiSnapshot(UUID.randomUUID().toString(),System.currentTimeMillis()/1000.0,stamp,packageName,
            bitmap.width,bitmap.height,numbered,hash)
        return VisualFrame(snapshot,hash,Base64.encodeToString(data,Base64.NO_WRAP),imageWidth,imageHeight)
    }
    fun wire(frame:VisualFrame):String=JSONObject(frame.snapshot.wire()).put("source","ocr")
        .put("screenshot_hash",frame.imageHash).put("screenshot",JSONObject().put("format","jpeg")
            .put("data_base64",frame.jpeg).put("width",frame.imageWidth).put("height",frame.imageHeight)).toString()
}
