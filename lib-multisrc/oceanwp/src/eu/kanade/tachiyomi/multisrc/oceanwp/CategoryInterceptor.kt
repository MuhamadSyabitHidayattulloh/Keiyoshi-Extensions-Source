package eu.kanade.tachiyomi.multisrc.oceanwp

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class CategoryInterceptor(
    private val callback: (List<Pair<String, String>>) -> Unit,
    private val shouldParse: () -> Boolean,
    private val parseCategories: (Document) -> List<Pair<String, String>>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        try {
            val body = response.body
            val contentType = body?.contentType()
            val isHtml = contentType?.subtype?.contains("html", ignoreCase = true) == true ||
                (contentType?.type?.equals("text", ignoreCase = true) == true)

            if (isHtml && body != null && shouldParse()) {
                val bytes = body.bytes()
                val content = bytes.toString(Charsets.UTF_8)

                try {
                    val doc = Jsoup.parse(content, request.url.toString())
                    val parsed = parseCategories(doc)
                    if (parsed.isNotEmpty()) {
                        callback(parsed)
                    }
                } catch (_: Exception) {
                }

                val newBody = bytes.toResponseBody(contentType)
                return response.newBuilder().body(newBody).build()
            }
        } catch (_: Exception) {
        }

        return response
    }
}
