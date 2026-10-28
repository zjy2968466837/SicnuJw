package cn.edu.sicnu.scnujw.core.auth

/**
 * 登录/换票功能开关。
 *
 * 依据 `LOGIN_SSO_PLAN.md` §0.4：静默换票在设备端门禁（E1/E2）通过前不得默认开启。
 * 2026-10-26 本轮设备验收通过后置 true；若线上出现登录成功率下降，第一动作是把这里改回 false，
 * 行为会退化为「每个系统一次 WebView SSO」，不会把用户打进登录页死循环。
 */
object AuthFeatureFlags {
    /** 是否允许用 CAS 票据静默换下游会话 */
    @Volatile
    var SILENT_TICKET: Boolean = true

    /** 原生课表（print-data）：2026-10-26 设备实测 22 条活动后开启 */
    @Volatile
    var NATIVE_SCHEDULE: Boolean = true

    /** 空闲教室去 WebView 依赖——未实施，保留给 step 11 */
    @Volatile
    var NATIVE_ROOMFREE: Boolean = false
}
