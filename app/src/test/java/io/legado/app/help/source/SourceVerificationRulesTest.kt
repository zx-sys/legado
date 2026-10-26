package io.legado.app.help.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceVerificationRulesTest {

    @Test
    fun `cloudflare challenge pages are detected`() {
        assertTrue(isVerificationChallengeHtml("<title>Just a moment...</title>"))
        assertTrue(isVerificationChallengeHtml("Checking your browser before accessing"))
        assertTrue(isVerificationChallengeHtml("Performing security verification"))
        assertTrue(isVerificationChallengeHtml("Verifying you are human"))
        assertTrue(isVerificationChallengeHtml("请完成安全验证"))
        assertTrue(isVerificationChallengeHtml("拖动滑块完成验证"))
    }

    @Test
    fun `normal content is not a challenge`() {
        assertFalse(
            isVerificationChallengeHtml(
                "<html><body><h1>第一章</h1><p>普通的小说正文，没有验证页面。</p></body></html>"
            )
        )
    }

    @Test
    fun `blank or failed page is treated as unsolved`() {
        assertTrue(isVerificationChallengeHtml(null))
        assertTrue(isVerificationChallengeHtml(""))
        assertTrue(isVerificationChallengeHtml("   "))
    }

    @Test
    fun `login forms are not treated as solved verification`() {
        assertTrue(isLoginFormHtml("<input type=\"password\" name=\"pwd\">"))
        assertTrue(isLoginFormHtml("<input type='password'>"))
        assertFalse(isLoginFormHtml("<html><body>chapter</body></html>"))
        assertFalse(isLoginFormHtml(null))
    }

    @Test
    fun `hidden login modal on a content page is not a login page`() {
        assertFalse(
            isLoginPageHtml(
                "<html><head><title>第一章-毕业考核</title></head><body>" +
                    "<input type=\"password\" name=\"pwd\"><p>正文</p></body></html>"
            )
        )
    }

    @Test
    fun `an actual login page is detected`() {
        assertTrue(
            isLoginPageHtml(
                "<html><head><title>用户登录</title></head><body>" +
                    "<form action=\"/login\"><input type=\"password\"></form></body></html>"
            )
        )
        assertTrue(
            isLoginPageHtml(
                "<html><head><title>Sign in</title></head><body>" +
                    "<input type=\"password\"></body></html>"
            )
        )
        assertFalse(isLoginPageHtml("<html><body>正文</body></html>"))
    }
}
