package cn.edu.sicnu.scnujw.core.jwcxk

/**
 * 空闲教室查询（jwcxk /student/for-std/room-free）数据模型。
 * 字段与树维 EAMS API 响应一一对应，见 room-free.js 与实测抓包。
 */
data class Campus(
    val id: String,
    val nameZh: String,
)

data class Building(
    val id: String,
    val nameZh: String,
)

data class RoomSimple(
    val id: String,
    val nameZh: String,
)

data class RoomType(
    val id: Int,
    val nameZh: String,
)

/** 节次（上午1/上午2/.../晚上），含时间段与分段 */
data class TimeUnit(
    val value: String,
    val name: String,
    val time: String,
    val changeTime: String,
    val dayPart: String,
)

/** 查询结果：空闲教室 */
data class FreeRoom(
    val id: Int,
    val nameZh: String,
    val campusName: String,
    val buildingName: String,
    val floor: Int,
    val seatsForLesson: Int,
    val roomTypeName: String,
    val remark: String,
)

/** free-list POST 请求体（字段顺序与前端 JS 一致） */
data class FreeListParam(
    val campusId: String,
    val buildingId: String = "",
    val roomId: String = "",
    val roomType: Int? = null,
    val seatsForLessonGte: Int? = null,
    val hasDataPermission: Boolean = false,
    val byWeek: Boolean = false,
    val byTime: Boolean = false,
    // 按教学周
    val weeks: List<String> = emptyList(),
    // 按日期
    val startDateTime: String = "",
    val endDateTime: String = "",
    // 按节次
    val units: List<String> = emptyList(),
    // 按时间
    val startTime: String = "",
    val endTime: String = "",
    // 星期几（1=周一 ... 7=周日）
    val weekdays: List<String> = emptyList(),
) {
    /** 序列化为前端 JS 提交的 JSON 结构 */
    fun toJsonBody(): String {
        val sb = StringBuilder("{")
        sb.append("\"buildingId\":\"$buildingId\",")
        sb.append("\"campusId\":\"$campusId\",")
        sb.append("\"roomId\":\"$roomId\",")
        roomType?.let { sb.append("\"roomType\":$it,") }
        seatsForLessonGte?.let { sb.append("\"seatsForLessonGte\":$it,") }
        sb.append("\"hasDataPermission\":$hasDataPermission,")

        // dateTimeSegmentCmd
        sb.append("\"dateTimeSegmentCmd\":{")
        if (!byWeek) {
            if (startDateTime.isNotEmpty()) sb.append("\"startDateTime\":\"$startDateTime\",")
            if (endDateTime.isNotEmpty()) sb.append("\"endDateTime\":\"$endDateTime\",")
        } else {
            if (weeks.isNotEmpty()) sb.append("\"weeks\":[${weeks.joinToString(",") { "\"$it\"" }}],")
        }
        if (!byTime) {
            if (units.isNotEmpty()) sb.append("\"units\":[${units.joinToString(",") { "\"$it\"" }}],")
        } else {
            if (startTime.isNotEmpty()) sb.append("\"startTime\":\"$startTime\",")
            if (endTime.isNotEmpty()) sb.append("\"endTime\":\"$endTime\",")
        }
        if (weekdays.isNotEmpty()) sb.append("\"weekdays\":[${weekdays.joinToString(",") { "\"$it\"" }}],")
        // 去掉最后一个逗号
        if (sb.endsWith(",")) sb.deleteCharAt(sb.length - 1)
        sb.append("}")

        sb.append("}")
        return sb.toString()
    }
}
