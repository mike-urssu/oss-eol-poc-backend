package cloud.jjoon.eolpoc.lifecycle

/** 버전을 . - _ 로 나눈 토큰 단위로 비교한다. 숫자 토큰은 숫자로, 나머지는 문자열로 비교한다(설계 4.7). */
object VersionComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val ta = tokens(a)
        val tb = tokens(b)
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]
            val y = tb[i]
            val nx = x.toBigIntegerOrNull()
            val ny = y.toBigIntegerOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c
        }
        return ta.size - tb.size
    }

    private fun tokens(v: String) = v.split('.', '-', '_')
}
