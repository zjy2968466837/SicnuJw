package cn.edu.sicnu.scnujw.ui.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.edu.sicnu.scnujw.core.update.UpdateCheck
import cn.edu.sicnu.scnujw.core.update.UpdateInfo
import cn.edu.sicnu.scnujw.core.update.UpdateManager
import java.io.File

/** 更新弹窗的阶段。下载进度与已下载文件都挂在同一个状态里，避免弹窗和协程各存一份 */
enum class UpdatePhase { Offer, Downloading, NeedPermission, Installing, Failed }

data class UpdateUiState(
    val info: UpdateInfo,
    val force: Boolean,
    val phase: UpdatePhase = UpdatePhase.Offer,
    val progress: Float = 0f,
    val message: String? = null,
    /** 已下载且通过签名校验的安装包；NeedPermission / Installing 阶段复用 */
    val pendingFile: File? = null,
)

/**
 * 更新弹窗的全局状态。放在 object 里是因为两个入口要共用同一个弹窗：
 *  - `UpdateHost` 在启动后自动检查；
 *  - 「我的」页脚「检查更新」手动触发。
 * 弹窗本身由 `UpdateHost` 在 NavHost 之上渲染，所以无论当前在哪个页面都能看到。
 */
object UpdateCenter {
    var state by mutableStateOf<UpdateUiState?>(null)
        private set

    fun offer(info: UpdateInfo, force: Boolean) {
        state = UpdateUiState(info, force)
    }

    fun update(block: (UpdateUiState) -> UpdateUiState) {
        state = state?.let(block)
    }

    fun dismiss() {
        state = null
    }

    suspend fun checkNow(ctx: Context, manual: Boolean): UpdateCheck {
        val result = UpdateManager.check(ctx, manual)
        if (result is UpdateCheck.Available) offer(result.info, result.force)
        return result
    }
}
