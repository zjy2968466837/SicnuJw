package cn.edu.sicnu.scnujw.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 回到前台时的「按天复检」钩子。
 *
 * 背景：各页的按天刷新只在页面重建时判断一次；App 一直挂在后台跨过午夜，再回来时
 * 页面不会重新判断缓存日期（2026-10-29 调查确认）。这里在每次 ON_RESUME 时触发一次
 * [onResume]，由页面调用自己的 `load()`——它内部仍按「缓存是不是今天」决定要不要联网，
 * 所以当天已刷过时几乎无成本。
 *
 * - 首次 ON_RESUME 跳过：首次进入已经由 `LaunchedEffect(Unit)` 触发加载，避免重复请求。
 * - 只接「按天刷新」的页面（选课成绩/学习进度/考试安排/选课结果）；平时成绩、学生卡、
 *   课表按各自策略，不接这个钩子。
 */
@Composable
fun ScnuResumeRefresh(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onResume)
    val seenFirstResume = remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!seenFirstResume.value) seenFirstResume.value = true else callback()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
