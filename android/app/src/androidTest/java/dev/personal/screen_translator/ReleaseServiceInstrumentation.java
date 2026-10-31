package dev.personal.screen_translator;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.ActivityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;

/** Tests the installed R8 component without JUnit dependencies stripped from the target APK. */
public final class ReleaseServiceInstrumentation extends Instrumentation {
    private boolean screenCheck;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        screenCheck = arguments != null && "true".equals(arguments.getString("screen"));
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            Class<?> component = Class.forName(getTargetContext().getPackageName()+".ScreenTranslateService", true, getTargetContext().getClassLoader());
            Throwable[] failure = new Throwable[1];
            runOnMainSync(() -> {
                try { component.getDeclaredConstructor().newInstance(); }
                catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw failure[0];
            report.putBoolean("constructorPassed", true);
            if (screenCheck) report.putString("screenCheckJson", checkScreen().toString());
            report.putString("stream", "Installed release service constructor: PASS\n");
            report.putBoolean("passed", true);
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            while (error.getCause() != null && (error instanceof RuntimeException || error instanceof InvocationTargetException)) error = error.getCause();
            report.putString("stream", "Installed release "+(screenCheck ? "screen check" : "service constructor")+": FAIL\n"+error+"\n");
            report.putBoolean("passed", false);
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private String label(AccessibilityNodeInfo node) {
        CharSequence value = node.getContentDescription();
        if (value == null || value.length() == 0) value = node.getText();
        return value == null ? "" : value.toString();
    }

    private AccessibilityNodeInfo findIn(AccessibilityNodeInfo node, String text, boolean clickable) {
        if (node == null || !getTargetContext().getPackageName().contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) return null;
        String value = label(node);
        if (node.isVisibleToUser() && value.split("\n")[0].equals(text) && (!clickable || node.isClickable() && node.isEnabled())) return node;
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findIn(node.getChild(i), text, clickable);
            if (match != null) return match;
        }
        return null;
    }

    private AccessibilityNodeInfo find(String text, boolean clickable) {
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo match = findIn(window.getRoot(), text, clickable);
            if (match != null) return match;
        }
        return null;
    }

    private AccessibilityNodeInfo waitNode(String text, boolean clickable, long timeout) {
        long deadline = SystemClock.elapsedRealtime()+timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo node = find(text, clickable);
            if (node != null) {
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (bounds.width()>0 && bounds.height()>0) return node;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Own control unavailable: "+text);
    }

    private void click(String text) {
        AccessibilityNodeInfo node = waitNode(text, true, 15_000);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Own control click failed: "+text);
    }

    private boolean serviceActive() {
        ActivityManager manager = getTargetContext().getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(20)) {
            if (info.service.getClassName().equals("dev.personal.screen_translator.ScreenTranslateService") && info.foreground) return true;
        }
        return false;
    }

    private void drag(Rect bounds) {
        long down = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int action : actions) {
            float x = action == MotionEvent.ACTION_DOWN ? bounds.left+4 : bounds.right-4;
            float y = action == MotionEvent.ACTION_DOWN ? bounds.top+4 : bounds.bottom-4;
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { if (!getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Own region drag failed"); }
            finally { event.recycle(); }
            SystemClock.sleep(150);
        }
    }

    private String translation() {
        // Android omits non-touchable overlays from accessibility's interactive window list.
        String[] result = new String[1];
        runOnMainSync(() -> {
            for (View root : WindowInspector.getGlobalWindowViews()) {
                View match = translationView(root);
                if (match != null) { result[0] = match.getContentDescription().toString(); break; }
            }
        });
        return result[0];
    }

    private View translationView(View node) {
        String text = String.valueOf(node.getContentDescription());
        if (node.isAttachedToWindow() && node.isShown() && text.startsWith("한국어 번역:") && text.contains("날씨") && text.contains("산책")) return node;
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i=0; i<group.getChildCount(); i++) {
                View match = translationView(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }

    private void checkDrawing(Rect source) {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                View match = null;
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    match = translationView(root);
                    if (match != null) break;
                }
                if (match == null) throw new AssertionError("Translation view disappeared");
                Bitmap bitmap = Bitmap.createBitmap(match.getWidth(), match.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    match.draw(new Canvas(bitmap));
                    int ink = 0, background = 0;
                    int[] location = new int[2]; match.getLocationOnScreen(location);
                    for (int y=Math.max(0,source.top-location[1]); y<Math.min(bitmap.getHeight(),source.bottom-location[1]); y++) {
                        for (int x=Math.max(0,source.left-location[0]); x<Math.min(bitmap.getWidth(),source.right-location[0]); x++) {
                            int pixel = bitmap.getPixel(x,y);
                            if (pixel == android.graphics.Color.WHITE) ink++;
                            if (pixel == android.graphics.Color.rgb(15,27,31)) background++;
                        }
                    }
                    if (ink<40 || background<100) throw new AssertionError("No translation text/background over source: "+ink+"/"+background);
                    try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), "release-translation.png"))) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
                    }
                } finally { bitmap.recycle(); }
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("Own overlay drawing failed: "+failure[0], failure[0]);
    }

    private void describeOwn(AccessibilityNodeInfo node, StringBuilder output) {
        if (node == null || output.length()>4000 || !getTargetContext().getPackageName().contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) return;
        if (node.isVisibleToUser() && !label(node).isEmpty()) output.append(label(node)).append(" | ");
        for (int i=0; i<node.getChildCount(); i++) describeOwn(node.getChild(i), output);
    }


    private JSONObject checkScreen() throws Exception {
        AccessibilityServiceInfo access = getUiAutomation().getServiceInfo();
        access.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(access);
        String pkg = getTargetContext().getPackageName();
        try {
            startActivitySync(new Intent().setClassName(pkg, pkg+".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            click("실시간 자동 번역");
            click("화면 번역 시작");
            Bundle instruction = new Bundle(); instruction.putString("stream", "\nApprove entire-screen sharing for the normal release own-sentence check.\n"); sendStatus(0, instruction);
            waitNode("번역 메뉴", true, 180_000);
            if (!serviceActive()) throw new AssertionError("Release sharing service did not stay active");
            click("직접 번역");
            AccessibilityNodeInfo source = waitNode("今日はいい天気ですね。少し散歩しませんか？", false, 15_000);
            Rect crop = new Rect(); source.getBoundsInScreen(crop);
            click("번역 메뉴"); click("번역할 영역 선택");
            waitNode("번역할 영역을 드래그하세요. 짧게 누르면 취소합니다.", false, 10_000);
            drag(crop);
            String first = null;
            for (int attempt=0; attempt<2; attempt++) {
                click("선택 영역 번역. 끌어서 버튼 이동");
                long deadline = SystemClock.elapsedRealtime()+20_000;
                String result = null;
                while (SystemClock.elapsedRealtime()<deadline) {
                    result = translation();
                    if (result != null) break;
                    if (!serviceActive()) throw new AssertionError("Release service exited during OCR");
                    SystemClock.sleep(100);
                }
                if (result == null) {
                    StringBuilder own = new StringBuilder();
                    for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) describeOwn(window.getRoot(), own);
                    throw new AssertionError("Own sentence OCR/translation not displayed; crop="+crop.toShortString()+"; own UI="+own);
                }
                checkDrawing(crop);
                if (first == null) first = result;
                else if (!first.equals(result)) throw new AssertionError("Repeated source translation changed");
                waitNode("선택 영역 번역. 끌어서 버튼 이동", true, 10_000);
            }
            long deadline = SystemClock.elapsedRealtime()+30_000;
            while (SystemClock.elapsedRealtime()<deadline) {
                if (!serviceActive() || find("번역 메뉴", true) == null) throw new AssertionError("Release service or controls disappeared during hold");
                SystemClock.sleep(500);
            }
            click("번역 메뉴"); click("화면 공유와 번역 종료");
            deadline = SystemClock.elapsedRealtime()+5_000;
            while (serviceActive() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100);
            if (serviceActive()) throw new AssertionError("Release service did not stop");
            if (find("번역 메뉴", true) != null) throw new AssertionError("Controls remain after stop");
            return new JSONObject().put("normalR8Release", true).put("screenTranslationPassed", true).put("repeatTranslationPassed", true)
                .put("drawingOverSourcePassed", true).put("serviceStayedActiveSeconds", 30).put("stopPassed", true).put("source", "今日はいい天気ですね。少し散歩しませんか？").put("translation", first);
        } finally {
            getTargetContext().stopService(new Intent().setClassName(pkg, pkg+".ScreenTranslateService"));
        }
    }
}
