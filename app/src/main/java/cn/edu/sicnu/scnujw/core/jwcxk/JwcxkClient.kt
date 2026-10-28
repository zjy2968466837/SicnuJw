package cn.edu.sicnu.scnujw.core.jwcxk

import cn.edu.sicnu.scnujw.core.net.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * 课表系统 jwcxk（上海树维 EAMS，JSON REST + 渲染页）。
 * 实测要点：
 *  - 入口必须先走 SSO 握手落至 "student/home"，直链 for-std 页面会被弹登录
 *  - 带 authserver 信任 Cookie 时 SSO 免 MFA 直通
 *  - 我的课表页 DOM 直出课程（名称、周次、节次、校区、教室），Jsoup 解析即可
 */
object JwcxkClient {
    private const val ORIGIN = "https://jwcxk.sicnu.edu.cn"
    const val SSO_ENTRY = "http://jwcxk.sicnu.edu.cn/student/sso/login"

    /** jwcxk 会话失效（302 登录页 / 401），需要用户重走 webauth */
    class JwcxkSessionExpiredException(message: String) : Exception(message)

    const val ROUTE_COURSE_TABLE = "/student/for-std/course-table"
    const val ROUTE_EXAM_ARRANGE = "/student/for-std/exam-arrange"
    const val ROUTE_STUDENT_INFO = "/student/for-std/student-info"
    const val ROUTE_ROOM_FREE = "/student/for-std/room-free"

    /** SSO 握手（跟随至 /student/home）后 GET 目标路由的渲染页。
     *  优先用 webauth（WebView 落地）回填的页面缓存：jwcxk 会话只活在 WebView
     *  上下文，OkHttp 重走 SSO 缺 authserver 票据（CASTGC）走不通。 */
    suspend fun fetchRenderedPage(route: String): org.jsoup.nodes.Document =
        withContext(Dispatchers.IO) {
            SessionManager.getJwcxkPage(route)?.let { return@withContext org.jsoup.Jsoup.parse(it) }

            var url: String = SSO_ENTRY
            var landed = false
            repeat(10) {
                val resp = SessionManager.client
                    .newCall(Request.Builder().url(url).build()).execute()
                val request = resp.request
                val hopUrl = request.url.toString()
                if (hopUrl.contains("/student/home")) landed = true
                val loc = resp.header("Location")
                if (loc == null) {
                    // 终点页：必须是业务页。CAS 登录页（会话失效）不能被当成课表解析成「0 门课」
                    val html = resp.use { it.body?.string() ?: "" }
                    if (isLoginPage(hopUrl, html)) {
                        throw JwcxkSessionExpiredException("jwcxk 会话已失效（被重定向到 CAS 登录页）")
                    }
                    return@withContext Jsoup.parse(html)
                }
                resp.close()
                url = runCatching { request.url.resolve(loc)?.toString() }.getOrNull() ?: loc
            }
            // 还没到 /student/home 又停在 CAS：会话没建立，不要再盲取业务页
            if (!landed && url.contains("authserver")) {
                throw JwcxkSessionExpiredException("jwcxk SSO 未落地（重定向停在 CAS 登录页）")
            }
            // 会话建立后取目标页（跟随最终渲染）
            val html = SessionManager.client
                .newCall(Request.Builder().url("$ORIGIN$route").build())
                .execute().use { r ->
                    if (r.code == 302 || r.code == 401) {
                        throw JwcxkSessionExpiredException("jwcxk 会话已失效（HTTP ${r.code}）")
                    }
                    check(r.code == 200) { "jwcxk 页面失败 ${r.code}" }
                    r.body?.string() ?: ""
                }
            if (isLoginPage("$ORIGIN$route", html)) {
                throw JwcxkSessionExpiredException("jwcxk 会话已失效（目标页跳到 CAS 登录页）")
            }
            Jsoup.parse(html)
        }

    /** CAS 统一认证登录页特征：URL 仍停在 authserver，或页面含统一认证表单/标题 */
    private fun isLoginPage(url: String, html: String): Boolean =
        url.contains("authserver.sicnu.edu.cn") ||
            html.contains("pwdFromId") ||
            html.contains("/authserver/login") ||
            html.contains("统一身份认证") ||
            html.contains("Unified identity authentication")

    /** JSON API（探测确认存在的）。
     *  会话失效时 jwcxk 返回 302 → 登录页（client 不跟随重定向，body 为空），
     *  空响应直接判为会话失效，避免下游 JSONException。 */
    suspend fun fetchJson(apiPath: String): String = withContext(Dispatchers.IO) {
        SessionManager.client
            .newCall(Request.Builder().url("$ORIGIN$apiPath").build())
            .execute().use { r ->
                if (r.code == 302 || r.code == 401) {
                    throw JwcxkSessionExpiredException("jwcxk 会话已失效（HTTP ${r.code}）")
                }
                check(r.code == 200) { "jwcxk API 失败 ${r.code}: $apiPath" }
                r.body?.string() ?: ""
            }
    }

    const val API_CURRENT_WEEK = "/student/home/get-current-teach-week"
    const val API_MENU = "/student/home/menu"

    // ── 空闲教室查询 API ──
    const val API_ROOM_FREE_LIST = "/student/ws/room-borrow/free-list"
    const val API_ROOM_BUILDINGS = "/student/ws/room/get-buildings"
    const val API_ROOM_ROOMS = "/student/ws/room/get-rooms"
    const val API_ROOM_UNIT_CAMPUS = "/student/ws/room-borrow/get-unit-campus"
    const val API_ROOM_TABLE_LAYOUT = "/student/ws/room-borrow/get-table-layout-campus"
    const val API_SUGGEST_WEEK = "/student/system/suggest-week"

    /** 空闲教室查询（POST JSON）。需要 jwcxk 会话（OkHttp CookieJar 或 webauth 缓存）。 */
    suspend fun fetchRoomFreeList(param: FreeListParam): List<FreeRoom> =
        withContext(Dispatchers.IO) {
            val body = param.toJsonBody()
                .toRequestBody("application/json".toMediaType())
            val resp = SessionManager.client
                .newCall(
                    Request.Builder()
                        .url("$ORIGIN$API_ROOM_FREE_LIST")
                        .post(body)
                        .build()
                )
                .execute()
            if (resp.code == 302 || resp.code == 401) {
                throw JwcxkSessionExpiredException("jwcxk 会话已失效（HTTP ${resp.code}）")
            }
            check(resp.code == 200) { "free-list 失败 ${resp.code}" }
            val json = resp.body?.string() ?: ""
            RoomFreeParser.parseFreeRooms(json)
        }

    /** 获取校区列表（空闲教室页初始化下拉） */
    suspend fun fetchCampuses(): List<Campus> = withContext(Dispatchers.IO) {
        val json = fetchJson(API_MENU)
        //  menu 接口返回的是菜单树，校区列表需另取；先用 home/get-current-teach-week 探活，
        //  实际校区列表由 get-table-layout-campus 或页面内嵌变量提供。
        //  简化：直接请求 get-buildings 不带 campusId 会报错，故此处返回空列表，
        //  由 UI 层在选定校区后联动加载。
        emptyList()
    }

    /** 获取教学楼列表（联动校区） */
    suspend fun fetchBuildings(campusId: String): List<Building> =
        withContext(Dispatchers.IO) {
            val json = fetchJson("$API_ROOM_BUILDINGS?campusId=$campusId&hasDataPermission=false")
            RoomFreeParser.parseBuildings(json)
        }

    /** 获取教室列表（联动教学楼） */
    suspend fun fetchRooms(buildingId: String): List<RoomSimple> =
        withContext(Dispatchers.IO) {
            val json = fetchJson(
                "$API_ROOM_ROOMS?buildingId=$buildingId&hasDataPermission=false&hasUsableDepartPermission=false"
            )
            RoomFreeParser.parseRooms(json)
        }

    /** 获取校区节次信息（联动校区，用于节次下拉） */
    suspend fun fetchUnitCampus(campusId: String): List<TimeUnit> =
        withContext(Dispatchers.IO) {
            val json = fetchJson("$API_ROOM_UNIT_CAMPUS?campusId=$campusId")
            RoomFreeParser.parseTimeUnits(json)
        }

    /** 获取校区布局（含学期起始日期，用于周次计算） */
    suspend fun fetchTableLayoutCampus(campusId: String): String? =
        withContext(Dispatchers.IO) {
            val json = fetchJson("$API_ROOM_TABLE_LAYOUT?campusId=$campusId")
            RoomFreeParser.parseSemesterStart(json)
        }
}
