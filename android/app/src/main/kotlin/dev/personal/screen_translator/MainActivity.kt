package dev.personal.screen_translator

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var captureReply: MethodChannel.Result? = null
    private var manualCapture = false
    private var automaticCapture = false
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Translator.initialize(applicationContext)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "screen_translate/events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) { Translator.listener = { sink.success(it) }; Translator.publish() }
                override fun onCancel(arguments: Any?) { Translator.listener = null }
            })
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "screen_translate/control").setMethodCallHandler { call, result ->
            when (call.method) {
                "state" -> result.success(Translator.snapshot())
                "licenses" -> result.success(mapOf(
                    "AIHub 일본어 → 한국어" to assets.open("licenses/AIHub-JA-KO.txt").bufferedReader().use { it.readText() },
                    "ONNX Runtime / MeCab / UniDic" to assets.open("licenses/ONNX-MeCab-UniDic.txt").bufferedReader().use { it.readText() }
                ))
                "overlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    result.success(Settings.canDrawOverlays(this))
                }
                "load" -> {
                    val id = call.argument<String>("id")
                    if (id == null) result.error("MODEL", "알 수 없는 모델입니다.", null)
                    else if (ScreenTranslateService.running) result.error("BUSY", "화면 번역을 먼저 정지해 주세요.", null)
                    else Translator.run(result) { Translator.load(id) }
                }
                "inspectModel" -> {
                    val url = call.argument<String>("url").orEmpty()
                    if (ScreenTranslateService.running) result.error("BUSY", "화면 번역을 먼저 종료해 주세요.", null)
                    else Translator.run(result) { Translator.downloads.inspect(url, Translator.models.first()) }
                }
                "installModel", "removeModel" -> {
                    val id = call.argument<String>("id").orEmpty()
                    if (ScreenTranslateService.running) result.error("BUSY", "화면 번역을 먼저 종료해 주세요.", null)
                    else Translator.run(result) {
                        if (call.method == "installModel") Translator.downloads.install(id) else Translator.removeModel(id)
                    }
                }
                "cancelDownload" -> { Translator.downloads.cancel(); result.success(null) }
                "translate" -> {
                    val text = call.argument<String>("text")?.trim().orEmpty()
                    if (text.isEmpty() || text.length > 700) result.error("INPUT", "1~700자의 일본어를 입력해 주세요.", null)
                    else if (ScreenTranslateService.running) result.error("BUSY", "화면 번역을 먼저 정지해 주세요.", null)
                    else Translator.run(result) { Translator.translate(text) }
                }
                "start" -> {
                    manualCapture = call.argument<Boolean>("manual") ?: true
                    automaticCapture = call.argument<Boolean>("automatic") ?: false
                    startCapture(result)
                }
                "stop" -> { startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.STOP)); result.success(null) }
                else -> result.notImplemented()
            }
        }
    }
    private fun startCapture(result: MethodChannel.Result) {
        when {
            captureReply != null -> result.error("BUSY", "화면 공유 요청이 진행 중입니다.", null)
            ScreenTranslateService.running -> result.success(null)
            Translator.loadedId == null || Translator.busy.get() -> result.error("MODEL", "모델 준비를 먼저 완료해 주세요.", null)
            !Settings.canDrawOverlays(this) -> result.error("OVERLAY", "다른 앱 위에 표시 권한을 먼저 허용해 주세요.", null)
            automaticCapture && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
                result.error("NOTIFICATIONS", "자동 번역은 알림에서 관리합니다. 알림을 허용한 뒤 다시 시작해 주세요. 창이 나오지 않으면 앱 설정에서 알림을 켜 주세요.", null)
            }
            else -> {
                captureReply = result
                val manager = getSystemService(MediaProjectionManager::class.java)
                val consent = if (Build.VERSION.SDK_INT >= 34 && !automaticCapture) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
                startActivityForResult(consent, 100)
            }
        }
    }
    @Deprecated("Android callback required by FlutterActivity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        val reply = captureReply ?: return
        captureReply = null
        if (resultCode != Activity.RESULT_OK || data == null) { reply.error("CANCELLED", "화면 공유를 취소했습니다.", null); return }
        startForegroundService(Intent(this, ScreenTranslateService::class.java).putExtra("permission", data).putExtra("resultCode", resultCode).putExtra("manual", manualCapture).putExtra("automatic", automaticCapture))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        reply.success(null)
    }
}
