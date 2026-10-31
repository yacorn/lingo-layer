package dev.personal.screen_translator

import org.junit.Assert.*
import org.junit.Test

class ModelUrlsTest {
    @Test fun publicRepositoryRootsAreParsed() {
        assertEquals("github.com" to "owner/model", ModelUrls.repository("https://GitHub.com/owner/model.git/"))
        assertEquals("huggingface.co" to "owner/model", ModelUrls.repository("https://huggingface.co/owner/model"))
    }
    @Test fun repositoryMustNotBeFileOrApiAddress() {
        for (value in listOf("https://huggingface.co/a/b/tree/main", "https://api.github.com/a/b", "https://github.com/a/b?ref=main"))
            assertThrows(Exception::class.java) { ModelUrls.repository(value) }
    }
    @Test fun untrustedHostsCredentialsAndCleartextAreRejected() {
        for (value in listOf("http://github.com/a/b", "https://github.com.evil.test/a/b", "https://user@huggingface.co/a/b",
            "file:///data/private", "https://localhost/a/b", "https://huggingface.co:444/a/b", "https://github.com/a/../b", "https://github.com/a/%2e%2e/b"))
            assertThrows(Exception::class.java) { ModelUrls.checked(value) }
    }
    @Test fun approvedCdnRedirectsRemainHttps() {
        ModelUrls.checked("https://cas-bridge.xethub.hf.co/download?signature=ok")
        ModelUrls.checked("https://release-assets.githubusercontent.com/file?signature=ok")
    }
}
