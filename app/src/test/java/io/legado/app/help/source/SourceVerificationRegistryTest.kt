package io.legado.app.help.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceVerificationRegistryTest {

    @Test
    fun `loginCheckJs with browser verification declares verification source`() {
        val js = """
            var isCf = function (r) {
                return r && /Just a moment|Cloudflare/.test(r.body());
            };
            if (isCf(result)) {
                java.startBrowserAwait(result.url(), "Cloudflare验证");
            }
        """.trimIndent()
        assertTrue(loginCheckJsDeclaresVerification(js))
    }

    @Test
    fun `ordinary login check is not a verification source`() {
        assertFalse(loginCheckJsDeclaresVerification(null))
        assertFalse(loginCheckJsDeclaresVerification(""))
        assertFalse(loginCheckJsDeclaresVerification("result.body().indexOf('登录') >= 0"))
    }

    @Test
    fun `browser await without challenge marker is not treated as verification`() {
        val js = "java.startBrowserAwait(url, '登录')"
        assertFalse(loginCheckJsDeclaresVerification(js))
    }

    @Test
    fun `challenge markers are detected case insensitively`() {
        assertTrue(containsVerificationMarker("Just a moment..."))
        assertTrue(containsVerificationMarker("请完成安全验证"))
        assertFalse(containsVerificationMarker("普通正文"))
    }
}
