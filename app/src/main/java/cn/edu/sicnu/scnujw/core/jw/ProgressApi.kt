package cn.edu.sicnu.scnujw.core.jw

/**
 * 学习进度接口：`ScoreQuery/wp_MyLearnProgress_Query.aspx` 服务端直出，
 * 无下拉筛选、无回发。实测（2026-10 样本）返回六类学分模块：
 * 文化素质/专业教育/自主发展 各自的必修与选修，含"最低学分要求"与表尾"获得学分"。
 */
suspend fun JwClient.fetchProgress(): LearnProgress =
    ProgressParser.parse(get(LEARN_PROGRESS))
