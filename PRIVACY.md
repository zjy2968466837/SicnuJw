# 隐私说明（数据收集清单）

> 本文件随仓库开源。任何新增上报字段都必须先改这里，再改代码。

## 一句话

App 只做三件与「收集」有关的事：使用统计（不可关，频率与字段见 §1）、更新埋点（随更新检查自动上报，见 §1）、诊断日志（默认关、可开，见 §2）。
另有两处**按需**：图书馆座位签到时申请定位，只用来算「你离座位多远」，坐标不上传、不落盘（见 §3.1）；提交报修 / 服务监督发帖只有你点「提交」时才会把内容发给学校后勤系统（见 §3.2）。

> 应用内通知只读取服务器公开的 `notices.json`，请求不带设备号、学号或已读状态，也不写入统计库。

---

## 1. 使用统计（上报到自建服务器）

### 上报字段（完整清单，代码见 `core/stats/StatsReporter.kt`）

| 字段 | 内容 | 用途 |
|---|---|---|
| `prefix4` | **学号前 4 位**（如 `2023`），客户端 `take(4)` | 按年级看使用分布 |
| `deviceId` | 本机随机生成的 UUID | 去重设备（不是硬件号、不是广告 ID） |
| `accountRef` | 本机随机生成的 UUID，按「设备 + 前缀」各一个 | 同一设备上的账号数（与学号无关） |
| `appVersion` / `versionCode` | 应用版本 | 版本分布 |
| `sdk` / `manufacturer` / `model` | Android 版本、厂商、机型 | 兼容性排查 |
| `locale` | 语言地区 | 文案问题定位 |

> 不上传 `firstSeenAt`（设备首见时间由服务端首次收到记录的时刻推算）。字段清单以 `core/stats/StatsReporter.buildPayload` 与 `deploy/stats/telemetry_contract.json` 为准。

### 绝不上报

- **完整学号**：客户端只在 `ScoreCache.onAccountKnown` 里对学号调用 `take(4)`，完整值不出这个函数；
  服务端也只接受 4 位数字，10 位学号会被直接拒绝（HTTP 400）。
- 姓名、性别、学院、专业、班级、成绩、课表、考试、选课、证件照。
- Cookie、CASTGC、票据、密码、页面内容。
- IMEI / 序列号 / Android ID / 广告 ID / 手机号 / 位置 / 通讯录。

### 关于「同账号多设备只算一次」

本条不做：跨设备账号关联必须上传学号派生标识，而 10 位学号的哈希可被穷举还原。因此本实现**不上传**任何学号派生标识；服务端统计口径是**设备数（devices）**，不是学生数；同一设备每天最多成功上报一次，换设备记两台。

### 加密与存储

- **传输**：只走 HTTPS（与更新站点同一域名/证书），明文 HTTP 直接不发（`endpoint()` 只接受 `https://`）。
- **本地**：`deviceId`/`accountRef` 用 Android Keystore（AES-GCM）加密后存 `shared_prefs/scnu_stats_secure.xml`；
  加密失败时退回存随机 UUID 明文（值本身不含个人信息），只记一条 debug 日志。
- **服务端**：落库前把两个标识换成 `HMAC-SHA256(pepper)`，pepper 只存在于服务器
  `/etc/sicnujw-stats.env`（600，不进仓库）。数据库里没有可直接关联到人的原始标识。
- 不做客户端「应用层 AES」：仓库是开源的，内嵌密钥等于公开，只会制造安全错觉。

### 频率与关闭

- **每台设备每天最多成功上报一次**（UTC 日期；用途：各年级使用人数与**当前版本分布**）；**当天版本变化（刚更新）再补报一次**，之后当天不再报。成功才写日期/版本标记；失败不写，当天再次登录或启动会重试。
- **统计没有运行时开关**：频率上限就是每天一次 + 每次版本变化一次，设置里没有开关；诊断日志的「发送诊断」不受影响。只有构建期 `statsEndpoint` 为空（例如公开构建）才完全不发。
- 统计地址由构建期 `statsEndpoint` 显式给出；**未配置的构建（例如不含该地址的公开构建）不会发任何统计请求**。

### 更新埋点

更新检查、下载、打开系统安装器时，异步上报一条事件，用来回答「谁更新失败、失败在哪一步、走的哪条通道」（清单通道顺序见 `GLOSSARY.md`）：

| 字段 | 内容 | 用途 |
|---|---|---|
| `eventId` | 客户端生成的随机 ID（`applied` 用「旧版本-新版本」固定值） | 重发去重，服务器不会重复计数 |
| `deviceId` | 与使用统计同一个本机随机 UUID | 同一设备的事件聚合 |
| `appVersion` / `versionCode` | 当前版本 | 区分 1.2/1.3/1.14 |
| `sdk` / `manufacturer` / `model` / `locale` | 系统与机型 | 兼容性定位 |
| `event` | `check_ok` / `check_fail` / `update_available` / `download_start` / `download_ok` / `download_fail` / `install_launch` / `applied` | 检查→下载→安装→升级的漏斗 |
| `targetVersion` | 要更新的版本 | 目标版本 |
| `fromVersionCode` / `fromVersionName` | 升级前的版本（仅 `applied` 事件） | 设备版本变更记录（旧 → 新） |
| `channel` | 命中的清单通道：`cloudbase` / `azure` / `gitee` / `github`（未知 host 按位置回退 `primary` / `backupN`；失败时是最后尝试的通道） | 区分主站/备用通道故障 |
| `elapsedMs` | 检查或下载耗时（毫秒） | 判断超时与慢链路 |
| `httpStatus` | 失败时的 HTTP 状态码（无则 0） | 区分 404 配置错误与 5xx 源站故障 |
| `force` | 是否强制更新（0/1） | 强制更新覆盖情况 |
| `errorClass` | 异常类名（如 `SSLHandshakeException`） | 定位 TLS/端口/中间设备 |
| `message` | 异常消息截断 160 字，**去掉 URL 查询串** | 辅助判断 |

- **不上报**：学号/账号/姓名、Cookie、票据、页面内容、IP、位置；`channel` 只是固定标签，不是主机名或 IP；
- 服务端把 `deviceId` 换成 `HMAC(pepper)` 后落库，不存 IP；
- **不丢**：事件先写入本机队列（`shared_prefs/scnu_update_queue.xml`，最多 40 条），发送成功即删；断网或进程被杀时留在本机，下次启动补发。队列只含上表字段，不含任何教务数据；
- 阈值：每设备 40 次/小时（2026-11-08 起取消 IP 限流）；埋点失败静默，不影响更新本身；
- **版本升级后的首次启动**会补报一次 `applied`（旧版本 → 新版本，含旧/新 versionCode 与 versionName），用于确认升级是否真的完成；
- 补报后展示**内置更新日志**；必须滑到最下方才能关闭，关闭后打开反馈群（群号 `1073600385`，复制到剪贴板并尝试拉起 QQ）。群号不参与任何上报。**首次安装不算升级**，不补报 `applied`、不展示更新日志。

### CloudBase 中转

部分网络无法直连 Azure，统计与更新埋点会先发到腾讯云 CloudBase 的 HTTP 云函数，再由它转发到 Azure：

- 中转只接收 `POST /v1/stats` 与 `POST /v1/update-event`，字段与上表完全相同；**不接收 APK、不接收诊断日志正文**；
- CloudBase/腾讯云作为第一段 TLS 终点，其调用日志可能包含源 IP、时间、UA 和请求体；Azure 统计服务仍**不存 IP**；
- 云函数本身不记录请求体；`/v1/report`（用户主动发送的诊断，1.5MB）**不走中转**，仍直连 Azure；
- 中转失败时客户端自动改直连 Azure；两条都失败的事件留在本机队列，下次启动补发。

---

## 2. 诊断日志（默认只在本机）

### 记录什么

**默认关闭**，在「我的 → 诊断日志」手动开启。记录两类内容，全部只在本机：

1. **发到解析器手里的原始响应**：
   - 教务 HTTP 接口：URL（`sid` 参数打码）、HTTP 状态、Content-Type、正文字符数、正文（最多 20 万字符；证明页原文也走这条路径，写入前按本节规则脱敏）；
   - WebView 主框架页：页面 `outerHTML`（最多 40 万字符）。
2. **关键事件**（一行一句摘要，不含页面原文）：
   - `auth`：登录成功/失败、静默换票结果——只写结果与系统名（正方/jwcxk/学工），**不写账号、密码、票据值**；
   - `parse`：解析失败（页面路径 + 结论，例如「找不到成绩表」），不写 HTML；
   - `net`：网络失败（主机 + 路径，不含 `sid` 参数；异常类名）；
   - `crash`：崩溃摘要（异常类名 + 前 8 帧堆栈）。

### 发送诊断（用户主动上传）

「诊断日志」页新增**发送诊断**：点按钮、填一句问题描述（可选），会上传两样东西：

- 最近约 **500 KB** 的诊断记录（与「导出日志」同一份内容，写入前脱敏、发送前再脱敏一次）；
- 你填写的文字说明（同样过一遍脱敏，最多 2000 字）。

约束：**只在点击「发送」时上传**，不自动、不后台发送；日志内容与导出完全相同，
学号/姓名/票据/Cookie/密码参数/身份证/手机号/邮箱等已替换为占位符；服务器只存
`HMAC-SHA256(deviceId)`，不存 IP、不存原始设备号。限流：设备 3 次/小时，客户端另有 60 秒冷却。反馈内容仅用于排查问题，不进入使用统计。

### 开启后的约束

**只落本机** `files/diag/`（应用私有目录），写入前脱敏、导出再脱敏一次；学工与微生活两域整域不抓；随时可关（关后连 WebView 的 JS 都不注入，也不再有 HTTP/事件记录）；单文件 8 MB、超出轮转，可随时清空。关闭时 `record` / `recordEvent` / `captureWebView` 在最外层直接返回；开启时确认一次。

**硬性不抓**：学工域 `zhxg.sicnu.edu.cn` 整域禁止（含假期去向登记——离校/留校/返校属于行踪信息）；
川师微生活域 `saas-wap.sicnu.edu.cn` / `saas-api.sicnu.edu.cn` 同样整域禁止（页面含姓名、手机号、
宿舍号、一卡通余额，接口响应是用户全量 JSON）。
判断在 `Diag.isBlocked`，命中直接丢弃，连 JS 都不注入。新增其它禁区时改这一个函数。

### 脱敏（写入时 + 导出时各执行一次，代码见 `Diag.sanitizeText`）

- 本人**姓名、学号**按精确值替换成 `<NAME>` / `<SID>`；
- `sid=` 查询参数、`CASTGC` / `JSESSIONID` / `SESSION` / `__pstsid__` / `srv_id` / `ticket` / `st` 参数
  → `<REDACTED>`；
- `password` / `pwd` / `passwd` 参数，以及**含 password 或 username/user/account 字样的
  `<input>` 标签的 value**（表单里密码常写成 `name=password value=…`，参数正则匹配不到，
  单独处理；CAS 登录页的用户名输入框里就是学号，一并打码）→ `<REDACTED>`；
- 18 位身份证、11 位手机号、邮箱 → `<ID18>` / `<PHONE>` / `<EMAIL>`；
- 川师微生活的 `userCode=`（微信 openid，等同身份凭据）/ `user_code=` 参数 → `<USERCODE>`；
- 一卡通付款码（epay `qrcode`/`qrcodeId`/`ticket`/`jwt`/`SESSION`）**等同支付凭据**：只存在内存，不写 SharedPreferences/文件、不进日志与诊断抓包，付款码页设 `FLAG_SECURE`，退后台立即清空；
- 一卡通门户会话（`;jsessionid`）同样只在内存 15 分钟；`persontop.jsp` / `myepay/index` 的 HTML 含姓名、学号、卡号与余额，**只做即时解析**，不写文件、不写日志、不进诊断上报；探针文件（若调试写入 cache）用完即删。
- 其余 10 位学号 → `<SID10>`。

脱敏是**幂等**的（导出时会再跑一遍，不会变成 `<SID><SID>`），单测见
`app/src/test/java/.../DiagSanitizeTest.kt`。

导出文件头部会写明版本、设备、条数与上述规则。**页面里仍可能有课程名/教师名/教室等校内信息**，
公开分享前请自己再过一遍。

### 存储与生命周期

- 只在应用私有目录 `files/diag/capture.jsonl`，**不联网**；
- 单文件 8 MB，超出后轮转保留上一份；
- 只有你点「导出日志」并选择保存位置时，才会写出文件；
- 随时可「清空」；卸载应用即全部删除。

---

## 3. 其它本地数据

| 数据 | 位置 | 说明 |
|---|---|---|
| 教务会话 / Cookie | `shared_prefs/scnu_session.xml`（Cookie 记录整包 AES-GCM 加密）+ `scnu_session_secure.xml`（CASTGC+指纹，Keystore） | 退出登录清除；Keystore 不可用时 Cookie 只留内存、不落盘 |
| 成绩/课表/平时成绩等缓存 | `files/score_cache/` | 退出登录保留以便同号复用；换学号登录时清空 |
| jwcxk 渲染页缓存 | `files/jwcxk_*.html` | 退出登录 / 换号时删除；只用于空闲教室下拉与原生课表兜底 |
| 全校班级课表 / 教室字典缓存 | `files/ref_cache/` | 只含全校公共课表与教室信息，不含学号/姓名；退出与换号都不清 |
| 证明页正文 / 导出 PDF | 内存（ViewModel/WebView）；PDF 只在用户点「保存 PDF」后写入系统 Download（Android 10+；26–28 为应用下载目录） | 证明页可能含姓名/学号/身份证号，**不落应用缓存、不自动上传**；诊断日志按 §2 记录并脱敏，只有用户主动「发送诊断」才随日志上传；App 之后不读取已保存的 PDF。自绘 A4 PDF 的取数与内联边界见 `MODULES.md` 打印证明 |
| 学号/姓名（缓存归属用） | `files/score_cache/profile.json` | 只在本机，用于判断缓存属于谁 |
| 川师微生活凭据（微信 openid + 用户信息） | `shared_prefs/scnu_wsl_secure.xml`（Keystore AES-GCM） | 退出登录清除；与 CAS 共用 Keystore 别名，登出后需回微信重绑 |
| 服务大厅目录 | `files/wsl_menu.json` | 公开菜单原文，不含账号信息；不随换号清 |
| 智能用电账户 / 账单快照 | `shared_prefs/wsl_electricity_cache_v1.xml` | 账户名、设备、余额、最近账单；按 `userCode` 隔离，无 TTL；仅本机，不上传 |
| 电费每日余额快照 | `shared_prefs/wsl_electricity_balance_v1.xml` | `userCode` + 账户 id + 读表日 + 账户余额；用于首页「昨日消费」（昨日余额 − 今日余额），仅本机、保留最近 8 天、不上传；不写日志、不截图 |
| 图书馆账号凭据（`ic-cookie`） | `shared_prefs/scnu_session.xml` 的 `cookie_records`（与其它 Cookie 同一份 AES-GCM blob） | 图书馆会话的登录态（统一认证或本地账号换来的）；退出图书馆账号或 CAS 登出时清除，内存 `LibicSession` 同步清（见 §3.3） |
| 图书馆通知 / 预约 / 违约 / 操作流水（页面数据） | 仅内存（`LibicNoticeCache` / 订单页 ViewModel） | 不落盘；正文可能含联系方式，预约/违约/流水含个人与座位信息 |
| 趣智校园账号 / 绑定设备 / 用水订单 | `shared_prefs/linyu_prefs.xml`（EncryptedSharedPreferences；Keystore 不可用时降级 `linyu_prefs_plain.xml`） | 手机号、`loginCode`、`userId/accountId/projectId`、姓名；绑定设备 `snCode/mac/名称/厂商/通道`；活跃订单、开阀时间、消费金额；退出登录清账号、绑定列表和活跃订单 |
| 住理饮水会话 / 进行中订单 / 常用设备 / 本次消费 | `shared_prefs/scnu_zhuli_secure.xml` + `scnu_zhuli_order.xml` + `scnu_zhuli_devices.xml` + `scnu_zhuli_last_bill.xml`（Keystore AES/GCM） | 只存 `platform_token` / `session_secret` / `server_addr` / 设备与订单 / 常用设备 / 最近一次金额；**不保存密码与图形验证码**；退出登录、结束订单或换账号时清除 |

住理登录签名使用官方 App 内置的公开 `APP_KEY`（`ZhuliSign.APP_KEY`），它是客户端协议常量，不是用户凭据，不落盘、不随账号变化。

### 3.1 定位（只在座位签到那一刻用）

- 申请时机：**只有**在「座位预约 → 我的预约」里点「签到 / 签退」且该预约要求定位时，才弹系统授权；拒绝则不发请求，并提示「没有定位权限，无法计算签到距离」。
- 用途：算出你与座位的球面距离，作为接口里的 `Range`（米）上报——座位接口本身**没有经纬度字段**，只收这个距离。
- 边界：**坐标不上传、不写文件、不进诊断日志**；距离超过 200 米直接本地拦下，不发请求。
- 与官方 H5 的差异：官方首页签到写死 `Range=10`、签退写死 `1111`（等于跳过距离校验）；本 App 如实计算，不做「假装在座位旁」。

### 3.2 提交给校方后勤的内容（只有你点「提交」时才发）

这两条是校方业务系统（青橄榄 SaaS）的写入口，**直连学校域名、不经自建服务器**，也不进使用统计或诊断日志的上传链路：

- **提交报修**：报修人姓名、手机号、详细地址、问题描述、现场照片 → `intelligent-maintenance.sicnu.edu.cn`。工单提交后**不能撤销**，提交前有二次确认。
- **服务监督发帖**：性质（投诉/建议）、两级分类、正文（≤300 字）、是否匿名 → `bus-appointment-wap.sicnu.edu.cn`。「匿名」只隐藏作者名，内容仍对全校可见。
- App 侧不持久化这些内容（表单只在页面内存里）；提交后离开页面即丢弃。

### 3.3 图书馆空间（libic）

- **一卡通（wallet）**：余额与按日流水**门户口径优先**（`EpayPortalApi`，CAS 换票；失败回退微生活 `VcardApi`），缓存只在内存；付款码/条码图不缓存、不落盘、不打印；写接口（充值/绑卡/提现）不接入。
- **本地账号与会话独立，登录可复用 CAS**：图书馆本地账号 = 学号 + 密码（默认身份证后 6 位）+ 4 位图形验证码，与统一认证口令、川师微生活都不是同一套。**密码只存在于登录页的内存状态**（登录成功后立即清空输入框），**不写 SharedPreferences、不写文件、不进日志、不上传**。登录也可以走学校统一身份认证：静默路径只复用已保存的 `CASTGC` + 设备指纹，**不新增任何凭据、不碰密码**；需要人工登录/多因子时在 WebView 里完成，输入只发生在 WebView 页面，App 代码不读取、不保存统一认证密码。
- **凭据**：服务端只认 `ic-cookie`（HttpOnly、会话级）；它随 `CookieStore` 加密落盘，token 只在内存。CAS 只用于换到 `ic-cookie`，libic 业务请求不带 CASTGC。退出图书馆账号（座位页「退出」）会先尽力通知服务端再清 cookie；CAS 登出会清 cookie 与内存 `LibicSession`（见 `SESSION_MODEL.md §1.3`）。
- **限流**：libic 请求串行且受 2.5s 闸门约束，范围与重试规则见 `SESSION_MODEL.md §1.3`。
- **他人的姓名/学号**：`/reserve` 的 `resvInfo[]`、`/reserve/record` 会返回同设备上**别人**的 `trueName`/`logonName`；代码只把 `logonName` 与本人比对出 `isMine` 布尔值，姓名/登录名**不进模型、不进日志、不上屏**。`/reserve/resvInfo`（我的预约）的 `resvMemberInfoList` 在服务端**不打码**（`/reserve/record` 会打码），代码不读该字段，也不把整份响应落盘。
- **本人资料**：`UserInfo` 含姓名、学号、一卡通号、手机号等（身份证字段实测空串），只放内存供会话与写预约使用；不写文件、不打日志。
- **违约与操作流水**：`/creditRec/getOwn` 返回违约类型（`creditKindName`）、扣分（`thisUseScore`）、座位名（`devName`）、时间；`/reserve/operate/rec` 返回某条预约的操作流水（`kind` + 时间 + 座位/房间/实验室）。两者都含个人与座位信息，**只在订单页展示、只留进程内存**，不写缓存、不导出、不上自建服务器。
- **通知正文**：首页整份通知只在进程内存（`LibicNoticeCache`），不落盘；正文可能含联系人/电话，进详情页时按 HTML 渲染，不做二次分发。
- **不上自建服务器**：libic 请求直连学校域名，不进使用统计、不进更新埋点；`Diag` 目前也不抓 libic 的 HTTP 响应（`LibicClient` 走原生 OkHttp，不经 `JwClient.execute`）。**若以后给 libic 加 HTTP 抓取，必须先把 `libic.sicnu.edu.cn` 加进 `Diag.isBlocked`**，否则响应里的他人姓名会进 `capture.jsonl`。
- **不缓存/不导出**：没有「导出预约记录」功能；页面数据离开即丢。

### 3.4 淋浴（趣智校园 / 凯路水控）

- **独立账号**：趣智校园账号 = 手机号 + 短信验证码（密码登录备用），与学校 CAS/一卡通不是同一套。登录后 `loginCode` 是会话令牌，等同于登录态；不要把它贴到聊天、Issue 或日志里。
- **本机保存**：`shared_prefs/linyu_prefs.xml`（EncryptedSharedPreferences）存手机号、`loginCode`、`userId/accountId/projectId`、姓名、绑定设备 `snCode/mac/名称/厂商/通道`、活跃订单、开阀时间与消费金额；Keystore 不可用时降级到 `linyu_prefs_plain.xml`（与 linyu 上游同口径）。**不保存密码、不保存完整账单原始响应。**
- **权限**：BLE 扫描/连接；Android 12+ 用 `BLUETOOTH_SCAN`（`neverForLocation`）与 `BLUETOOTH_CONNECT`，Android 11 及以下复用 App 已有的定位权限（系统对 BLE 扫描的硬性要求，不额外上传坐标）。**扫码绑定**只在点「扫码添加」时申请相机权限，相机画面只用于识别 `KLCXKJ-Water` 二维码，**不拍照、不录像、不写文件、不上传**；也支持从相册选图识别，图片只在内存解码。用水期间启前台服务（`specialUse`）并显示通知；Android 13+ 需通知权限，拒绝后仍可开关水，但收不到状态提醒。
- **网络边界**：登录、余额、设备、订单、结算全部直连趣智校园 `https://v3-api.china-qzxy.cn`，**不进 ScnuJw 自建统计/诊断上传链路**；MVP 不接 MQTT。趣智校园是第三方服务，它能看到你的手机号、设备与用水订单，其隐私政策由服务商负责。
- **通知**：本地通知含设备名、用时、消费金额；锁屏可能可见，可在系统设置里关闭。
- **充值**：无公开充值 API；余额卡的充值入口只做提示（点击 `Toast`「充值暂未开放，请到趣智校园小程序充值」），不打开官方 App、不发支付请求、不读取或写入官方 App 的任何数据，充值过程完全在官方小程序/官方 App 内完成。
- **单设备登录**：趣智校园同一账号只能一端在线；本 App 登录会把官方 App 挤下线，反之亦然。这是服务端会话策略，不是数据泄露。
- **删除**：退出登录清账号、绑定设备列表、活跃订单与 BLE 会话；卸载 App 清全部本地数据。服务端订单/账单仍保存在趣智校园账户里。

### 3.5 顺丰取件（驿收发 / MIMP）

- **独立账号**：顺丰 MIMP 走手机号 + 短信验证码 + 极验 v4 滑块，与学校 CAS/一卡通不是同一套；不读取微信登录态。登录页由 `SfLoginActivity` 承载顺丰官方 H5，App 只读取登录后的会话 Cookie，不接触短信验证码。
- **本机保存**：`shared_prefs/scnu_yishoufa_secure.xml`（Keystore AES/GCM）存手机号、MIMP 会话 Cookie、身份码 10 位静态段。**不保存短信验证码**；静态段与 Cookie 等同于取件凭据，不写日志、不外传。
- **展示**：身份码条码本地逐秒生成，不发请求（公式见 `MODULES.md` 身份码 / 顺丰）；待取件列表来自 `queryStudentWaittingWaybill`，含驿站名、取件码、寄/收联系人（联系人中的 11 位手机号由本机脱敏后显示；姓名原样展示，以顺丰返回为准）。页面不提供截图分享按钮。
- **网络边界**：登录/出码/待取件直连 `https://mcs-mimp-web.sf-express.com`，**不进 ScnuJw 自建统计/诊断上传链路**；极验 SDK 由 `static.geetest.com` 提供，会拿到滑块交互数据与网络信息。顺丰是第三方服务，其隐私政策由顺丰负责。`YishoufaProtocol` 中的 MIMP 签名密钥是顺丰 H5 内置的公开常量，不是用户凭据。
- **只读**：不代取、不下单、不修改手机号/地址、不操作运单。
- **删除**：退出登录清除 Cookie、静态段与手机号；换号/统一登出同样清空。

### 3.6 京东 / 淘宝取件（身份码 / 取件码）

- **京东**：身份码与运单列表只加载官方 H5（`convenient-h5.jd.com/identityCode`、`kd.jd.com/express/scannedCopies`、`orderDetail`）；登录态是 `pt_key`/`pt_pin`，存 `scnu_pickup_secure.xml`（Keystore AES/GCM）。不代取、不下单。
- **淘宝**：身份码加载淘宝小程序页 `pages-fast.m.taobao.com/.../1011717/.../identity-code`，复用 WebView 里的淘宝登录 Cookie；页面在 App 内不渲染时，用 `com.taobao.taobao/com.taobao.browser.BrowserActivity` + `ACTION_VIEW` 打开淘宝 App，数据随后由淘宝 App 处理，受淘宝隐私政策约束。App 不读取淘宝 App 内的数据。
- **取件列表缓存**：`shared_prefs/scnu_pickup_cache.xml` 保存最近一次成功读到的待取/已取列表，7 天过期；只存公司/取件码/驿站/货架/运单号/到站描述，**不存页面原文、收件人姓名或手机号**。App 统一登出或京东「退出登录」时清空。
- **网络边界**：以上页面/接口直连京东、淘宝/菜鸟官方域名，不进 ScnuJw 自建统计/诊断上传链路；取件码与身份码不写日志、不外传。

### 3.7 川师微生活微信绑定（本地网络回调）

- **只在绑定页开端口**：进入 `wsl/bind` 时，前台服务在手机当前局域网 IPv4（优先 Wi-Fi/有线）上监听一个随机高位端口；拿不到可用地址时回退 `127.0.0.1`。路径带 128-bit 随机 token，只处理一次带微信 OAuth `code` 的 GET，成功/失败后关闭，或在 6 分钟超时/退出页面时随服务停止。端口只绑定这一个本机地址，不绑 `0.0.0.0`。
- **不扫描局域网**：不调用 mDNS/SSDP/NsdManager，不枚举、不连接同网段其它设备，也不读取回调请求以外的数据。
- **权限**：`ACCESS_LOCAL_NETWORK` 只在 App `targetSdk >= 37` 且 Android 17+ 时申请；当前 target 36 在 Android 17 走 `INTERNET` 隐式授权，Android 16 本地网络默认开放。ColorOS 等系统的「应用联网管理 / WLAN / 本地网络」开关由用户控制，App 不绕过。
- **数据边界**：局域网回调只承载微信 OAuth `code`（URL 里可能带 `tenantCode`，App 只读 `code`）；`code` 不落盘，随后的 `wechat/openid` 与 `wap/vcard/user-info` 走 HTTPS 到 `saas-api.sicnu.edu.cn`。换到的 `userCode`（openid）与用户信息用 Keystore AES/GCM 存 `scnu_wsl_secure.xml`（见 §3 表），退出登录清除。
- **HTTP 说明**：回调是 `http://<局域网 IP>:<随机端口>`，不是 HTTPS；一次性随机 token + 只收一次 + 短时关闭限制了被同网段利用的窗口，但同网段仍可能看到明文 `code`。要彻底避免，需改用 HTTPS 回调 BFF（见工作区 `scnu-wsl/HANDOFF.md`）。

---

## 4. 变更记录

历次边界调整见 `CHANGELOG.md`；本文件只描述当前承诺。
