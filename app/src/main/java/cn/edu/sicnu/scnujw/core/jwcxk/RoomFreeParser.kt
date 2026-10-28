package cn.edu.sicnu.scnujw.core.jwcxk

import org.json.JSONArray
import org.json.JSONObject

/**
 * 空闲教室查询 API 响应解析器。
 * 对应 jwcxk room-borrow 与 room 相关端点。
 */
object RoomFreeParser {

    /** 解析校区列表（get-buildings 前置下拉） */
    fun parseCampuses(json: String): List<Campus> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Campus(
                id = o.getString("id"),
                nameZh = o.getString("nameZh"),
            )
        }
    }

    /** 解析教学楼列表 */
    fun parseBuildings(json: String): List<Building> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Building(
                id = o.getString("id"),
                nameZh = o.getString("nameZh"),
            )
        }
    }

    /** 解析教室列表（某教学楼下的房间） */
    fun parseRooms(json: String): List<RoomSimple> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RoomSimple(
                id = o.getString("id"),
                nameZh = o.getString("nameZh"),
            )
        }
    }

    /** 解析教室类型列表 */
    fun parseRoomTypes(json: String): List<RoomType> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RoomType(
                id = o.getInt("id"),
                nameZh = o.getString("nameZh"),
            )
        }
    }

    /** 解析节次列表（get-unit-campus） */
    fun parseTimeUnits(json: String): List<TimeUnit> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TimeUnit(
                value = o.getString("value"),
                name = o.getString("name"),
                time = o.optString("time", ""),
                changeTime = o.optString("changeTime", ""),
                dayPart = o.optString("dayPart", ""),
            )
        }
    }

    /** 解析空闲教室查询结果（free-list） */
    fun parseFreeRooms(json: String): List<FreeRoom> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("roomList") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val building = o.optJSONObject("building")
            val campus = building?.optJSONObject("campus")
            val roomType = o.optJSONObject("roomType")
            FreeRoom(
                id = o.getInt("id"),
                nameZh = o.getString("nameZh"),
                campusName = optText(campus, "nameZh"),
                buildingName = optText(building, "nameZh"),
                floor = o.optInt("floor", 0),
                seatsForLesson = o.optInt("seatsForLesson", 0),
                roomTypeName = optText(roomType, "nameZh"),
                remark = optText(o, "remark"),
            )
        }.sortedBy { it.nameZh }
    }

    /** org.json 的 optString 会把 JSON null 返回成字面量 "null"，这里统一归一为空串 */
    private fun optText(o: JSONObject?, key: String): String {
        val v = o?.optString(key, "") ?: ""
        return if (v == "null") "" else v
    }

    /** 解析学期起始日期等（get-table-layout-campus） */
    fun parseSemesterStart(json: String): String? {
        val root = JSONObject(json)
        return root.optString("startDate", null)
    }
}
