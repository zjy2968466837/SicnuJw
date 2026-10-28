package cn.edu.sicnu.scnujw.core.zhxg

import org.json.JSONObject

/**
 * 智慧学工（zhxg.sicnu.edu.cn）响应解析。
 * 接口统一信封：{ errorMsg, resultCode, success, result: { data, total } }（.rst 系）。
 * 本文件只做纯函数 JSON → 数据类，不做网络与 IO。
 */

/* ─────────────────────────── 假期批次 ─────────────────────────── */

/** 假期批次（defaultBatch.rst / queryHolidayName.rst 的条目） */
data class HolidayBatch(
    val id: Long,
    val schoolYear: String,
    val holidayName: String,
    val holidayType: String,
    val startDate: String,
    val endDate: String,
    val leaveRegisterStartTime: String,
    val leaveRegisterEndTime: String,
    val stayRegisterStartTime: String,
    val stayRegisterEndTime: String,
    val returnRegisterStartTime: String,
    val returnRegisterEndTime: String,
    val earliestLeaveDate: String,
    val latestLeaveDate: String,
    val leaveNeedApply: Int,
    val stayNeedApply: Int,
    val returnNeedRegister: Int,
    val description: String,
)

/** 去向登记单条记录（myHoliday.rst → result.data.registers[]） */
data class HolidayRegister(
    val id: Long,
    val scheduleTypeDesc: String,   // 离校 / 留校
    val reason: String,             // 去向事由 / 留校原因
    val startDate: String,
    val endDate: String,
    val destination: String,        // 目的地（省,市,区）
    val travelType: String,         // 出行方式
    val auditStatus: String,        // 审核状态（已通过/待审核…）
    val status: Int,                // 服务端状态码（1 在途 / 2 已通过 / 3 已退回…）
    val canTakeBack: Boolean,
    val activityDefName: String,    // 流程节点（结束 等）
)

/** 返校登记单条记录（myHoliday.rst → result.data.returnRegisters[]） */
data class HolidayReturn(
    val id: Long,
    val registerStatus: String,     // 已登记
    val returnStatusDesc: String,   // 已返校 / 未返校
    val returnTypeDesc: String,     // 正常返校 等
    val realReturnDate: String,
    val realReturnTime: String,
    val confirmStatusDesc: String,  // 未确认 / 已确认
)

/** 我的假期登记聚合（myHoliday.rst → result.data） */
data class MyHoliday(
    val studentCode: String,
    val studentName: String,
    val orgName: String,
    val majorName: String,
    val classFName: String,
    val grade: String,
    val ethnicity: String,
    val campusName: String,
    val dormInfo: String,
    val phone: String,
    val studentType: String,
    val registers: List<HolidayRegister>,
    val returnRegisters: List<HolidayReturn>,
    /** 原始未识别字段兜底：label → value（详情渲染用） */
    val extras: Map<String, String>,
)

/** 动态表单字段（formPage.rst / submit detail.rst → result.data.fields 扁平 KV） */
data class FormDetail(
    val title: String,
    val fields: List<Pair<String, String>>,   // 有序 (label, value)
)

object HolidayParser {

    /** 解开 .rst 统一信封，返回 result.data 对象；解不开抛异常由上层 runCatching 兜底 */
    fun unwrapData(body: String): JSONObject {
        val root = JSONObject(body)
        if (root.has("success") && !root.optBoolean("success", true)) {
            val msg = root.optString("errorMsg").let { if (it == "null") "" else it }
            if (msg.contains("未登录") || msg.contains("登录已")) throw ZhxgClient.NotLoggedInException()
            throw IllegalStateException(msg.ifBlank { "接口返回失败" })
        }
        // 会话失效实测为 HTTP 200 + {"resultCode":-200,"errorMsg":"用户未登录！"}（无 success/result）：
        // 不能当正常数据返回，否则「未登录」会被上层渲染成「加载成功但批次为空」。
        if (root.has("resultCode") && root.optInt("resultCode", 0) != 0) {
            val msg = root.optString("errorMsg").let { if (it == "null") "" else it }
            if (root.optInt("resultCode") == -200 || msg.contains("未登录") || msg.contains("登录已")) {
                throw ZhxgClient.NotLoggedInException()
            }
            throw IllegalStateException(msg.ifBlank { "接口返回失败（resultCode=${root.optInt("resultCode")}）" })
        }
        val result = if (root.has("result")) root.getJSONObject("result")
        else root // 个别接口可能直出 data
        return if (result.has("data")) result.get("data") as? JSONObject
            ?: throw IllegalStateException("data 非对象")
        else result
    }

    /** 解数组型 data（queryHolidayName 等） */
    fun unwrapArray(body: String): org.json.JSONArray {
        val root = JSONObject(body)
        val result = if (root.has("result")) root.getJSONObject("result") else root
        return if (result.has("data")) result.getJSONArray("data")
        else org.json.JSONArray()
    }

    fun parseBatch(body: String): HolidayBatch? {
        val d = unwrapData(body)
        if (d.length() == 0) return null
        return HolidayBatch(
            id = d.optLong("id"),
            schoolYear = d.optString("schoolYear"),
            holidayName = d.optString("holidayName"),
            holidayType = d.optString("holidayType"),
            startDate = d.optString("startDate"),
            endDate = d.optString("endDate"),
            leaveRegisterStartTime = d.optString("leaveRegisterStartTime"),
            leaveRegisterEndTime = d.optString("leaveRegisterEndTime"),
            stayRegisterStartTime = d.optString("stayRegisterStartTime"),
            stayRegisterEndTime = d.optString("stayRegisterEndTime"),
            returnRegisterStartTime = d.optString("returnRegisterStartTime"),
            returnRegisterEndTime = d.optString("returnRegisterEndTime"),
            earliestLeaveDate = d.optString("earliestLeaveDate"),
            latestLeaveDate = d.optString("latestLeaveDate"),
            leaveNeedApply = d.optInt("leaveNeedApply"),
            stayNeedApply = d.optInt("stayNeedApply"),
            returnNeedRegister = d.optInt("returnNeedRegister"),
            description = stripHtml(d.optString("description")),
        )
    }

    fun parseHolidayNames(body: String): List<HolidayBatch> {
        val arr = unwrapArray(body)
        val out = mutableListOf<HolidayBatch>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            out.add(HolidayBatch(
                id = d.optLong("batchId"),
                schoolYear = "",
                holidayName = d.optString("holidayName"),
                holidayType = "",
                startDate = d.optString("startDate"),
                endDate = d.optString("endDate"),
                leaveRegisterStartTime = "", leaveRegisterEndTime = "",
                stayRegisterStartTime = "", stayRegisterEndTime = "",
                returnRegisterStartTime = "", returnRegisterEndTime = "",
                earliestLeaveDate = "", latestLeaveDate = "",
                leaveNeedApply = 0, stayNeedApply = 0, returnNeedRegister = 0,
                description = "",
            ))
        }
        return out
    }

    fun parseMyHoliday(body: String): MyHoliday {
        val d = unwrapData(body)
        val registers = mutableListOf<HolidayRegister>()
        d.optJSONArray("registers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                registers.add(HolidayRegister(
                    id = r.optLong("id"),
                    scheduleTypeDesc = r.optString("scheduleTypeDesc"),
                    reason = r.optString("reason"),
                    startDate = r.optString("startDate"),
                    endDate = r.optString("endDate"),
                    destination = r.optString("destination"),
                    travelType = r.optString("travelType"),
                    auditStatus = r.optString("auditStatus"),
                    status = r.optInt("status"),
                    canTakeBack = r.optBoolean("canTakeBack"),
                    activityDefName = r.optString("activityDefName"),
                ))
            }
        }
        val returns = mutableListOf<HolidayReturn>()
        d.optJSONArray("returnRegisters")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                returns.add(HolidayReturn(
                    id = r.optLong("id"),
                    registerStatus = r.optString("registerStatus"),
                    returnStatusDesc = r.optString("returnStatusDesc"),
                    returnTypeDesc = r.optString("returnTypeDesc"),
                    realReturnDate = r.optString("realReturnDate"),
                    realReturnTime = r.optString("realReturnTime"),
                    confirmStatusDesc = r.optString("confirmStatusDesc"),
                ))
            }
        }
        // 兜底字段：把页面详情中出现过但上面未单列的扁平 KV 全收进来
        val known = setOf(
            "registers", "returnRegisters",
            "studentCode", "studentName", "orgName", "majorName", "classFName",
            "grade", "ethnicity", "campusName", "dormInfo", "phone", "studentType",
        )
        val extras = LinkedHashMap<String, String>()
        for (k in d.keys()) {
            if (k in known) continue
            val v = d.opt(k) ?: continue
            if (v is String && v.isNotBlank()) extras[k] = stripHtml(v)
        }
        return MyHoliday(
            studentCode = d.optString("studentCode"),
            studentName = d.optString("studentName"),
            orgName = d.optString("orgName"),
            majorName = d.optString("majorName"),
            classFName = d.optString("classFName"),
            grade = d.optString("grade"),
            ethnicity = d.optString("ethnicity"),
            campusName = d.optString("campusName"),
            dormInfo = d.optString("dormInfo"),
            phone = d.optString("phone"),
            studentType = d.optString("studentType"),
            registers = registers,
            returnRegisters = returns,
            extras = extras,
        )
    }

    /**
     * 解析登记详情（formPage.rst / submit detail.rst）。
     * 实测 result.data.fields 为扁平 KV（label 即 key）；若是嵌套对象则递归拍平。
     */
    fun parseFormDetail(body: String, title: String): FormDetail {
        val d = unwrapData(body)
        val fields = mutableListOf<Pair<String, String>>()
        val labelOf = d.optJSONObject("label") // 某些变体 label 单独挂
        collectFields(d.opt("fields"), labelOf, "", fields)
        if (fields.isEmpty()) {
            // 无 fields 结构时按顶层 KV 兜底
            for (k in d.keys()) {
                if (k in setOf("fields", "label", "defaultRequestParams")) continue
                val v = d.opt(k)
                if (v is String && v.isNotBlank() && v.length < 200)
                    fields.add(k to stripHtml(v))
            }
        }
        return FormDetail(title, fields)
    }

    private fun collectFields(
        node: Any?, labelOf: JSONObject?, prefix: String,
        out: MutableList<Pair<String, String>>,
    ) {
        when (node) {
            is JSONObject -> for (k in node.keys()) {
                val v = node.opt(k) ?: continue
                if (v is JSONObject) {
                    collectFields(v, labelOf, k, out)
                } else {
                    val text = when (v) {
                        is String -> stripHtml(v)
                        is Boolean -> if (v) "是" else "否"
                        is Number -> v.toString()
                        else -> continue
                    }
                    if (text.isBlank() || text == "null" || text == "-") continue
                    val label = labelOf?.optString(k)?.takeIf { it.isNotBlank() } ?: k
                    out.add(label to text)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    val item = node.opt(i) ?: continue
                    if (item is JSONObject) collectFields(item, labelOf, prefix, out)
                }
            }
        }
    }

    /** 当前用户信息（/edu/api/v1/user/getinfo）：优先取 data，直出则原样 */
    fun parseUserInfo(body: String): Pair<String, String> {
        val root = JSONObject(body)
        val d = if (root.has("data")) root.getJSONObject("data") else root
        val code = d.optString("userName").ifBlank { d.optString("code") }
        val name = d.optString("name").ifBlank { d.optString("realName") }
        return code to name
    }

    /** 会话是否有效：getinfo 401/302 或无登录标识即失效 */
    fun isLoggedIn(body: String, httpCode: Int): Boolean =
        httpCode == 200 && runCatching {
            val root = JSONObject(body)
            val d = if (root.has("data")) root.getJSONObject("data") else root
            d.optString("userName").isNotBlank() || d.optString("code").isNotBlank() ||
                d.optString("name").isNotBlank()
        }.getOrDefault(false)

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
}
