package io.github.gkdhide.gkdx

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.gkdhide.gkdx.ui.DisabledStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 界面变化感知（无障碍事件的进程内替代）：
 * - Activity.onResume：记录当前 Activity，并按开屏时序安排多轮评估（0/400/900/1500/2500ms）
 * - TextView.setText / ViewGroup.addView：内容变化信号，150ms 去抖后评估
 */
object TriggerManager {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pkg: String = ""
    @Volatile private var currentActivity: Activity? = null
    private val pending = AtomicBoolean(false)
    private val evaluating = AtomicBoolean(false)

    fun install(packageName: String) {
        pkg = packageName

        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val activity = param.thisObject as Activity
                currentActivity = activity
                for (delay in longArrayOf(0, 400, 900, 1500, 2500)) {
                    handler.postDelayed({ evaluate() }, delay)
                }
            }
        })

        XposedHelpers.findAndHookMethod(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                if (currentActivity === param.thisObject) currentActivity = null
            }
        })

        XposedHelpers.findAndHookMethod(
            TextView::class.java, "setText", CharSequence::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    schedule()
                }
            })

        // 所有 addView 重载最终都走 addView(View, int, LayoutParams)
        XposedHelpers.findAndHookMethod(
            ViewGroup::class.java, "addView",
            View::class.java, Int::class.javaPrimitiveType, ViewGroup.LayoutParams::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    schedule()
                }
            })
    }

    private fun schedule() {
        if (pending.compareAndSet(false, true)) {
            handler.postDelayed({
                pending.set(false)
                evaluate()
            }, 150)
        }
    }

    private fun evaluate() {
        if (!evaluating.compareAndSet(false, true)) return
        try {
            if (SubscriptionStore.get() == null) return
            if (DisabledStore.isAppDisabled(DisabledCache.get(), pkg)) return
            val activity = currentActivity ?: return
            val roots = collectRoots()
            if (roots.isEmpty()) return
            RuleEngine.evaluate(pkg, activity, roots)
        } catch (t: Throwable) {
            XposedBridge.log("[GKDX] 评估异常")
            XposedBridge.log(t)
        } finally {
            evaluating.set(false)
        }
    }

    /** 枚举进程内所有已附加的窗口根 View（含 Dialog/弹窗窗口） */
    private fun collectRoots(): List<View> {
        return try {
            val wmgClass = XposedHelpers.findClass(
                "android.view.WindowManagerGlobal", View::class.java.classLoader)
            val global = XposedHelpers.callStaticMethod(wmgClass, "getInstance")
            @Suppress("UNCHECKED_CAST")
            val views = XposedHelpers.getObjectField(global, "mViews") as? ArrayList<View>
                ?: return emptyList()
            views.filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
        } catch (t: Throwable) {
            currentActivity?.window?.decorView?.let { listOf(it) } ?: emptyList()
        }
    }
}
