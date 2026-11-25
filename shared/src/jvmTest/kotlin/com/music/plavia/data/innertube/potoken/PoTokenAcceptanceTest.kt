package com.music.plavia.data.innertube.potoken

import com.metrolist.innertubex.extraction.PoTokenResult as InnerTubePoTokenResult
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's BotGuard minter returns a Uint8Array. This runs that page's
 * obtainPoToken — the function that was throwing the mint away — and the
 * encoder that pastes the result onto InnerTubeX's player request.
 */
class PoTokenAcceptanceTest {

    @Test
    fun pageDoesNotDiscardAMinterTokenByLength() {
        assertFalse(PO_TOKEN_HTML.contains("expected 110-128"))
        assertFalse(PO_TOKEN_HTML.contains("SZ:Short"))
        assertTrue(PO_TOKEN_HTML.contains("instanceof Uint8Array"))
        assertTrue(PO_TOKEN_HTML.contains("SZ:Empty"))
    }

    @Test
    fun realObtainPoTokenAcceptsTheSizesThePhoneMints() {
        val script = File.createTempFile("obtain-potoken", ".mjs")
        script.writeText(
            """
            import fs from 'fs';
            const html = fs.readFileSync(process.argv[2], 'utf8');
            const start = html.indexOf('async function obtainPoToken');
            const end = html.indexOf('</script>', start);
            if (start < 0 || end < 0) throw new Error('obtainPoToken missing');
            const obtainPoToken = new Function(html.slice(start, end) + '\nreturn obtainPoToken;')();
            const sizes = [88, 529, 533, 534, 535];
            for (const n of sizes) {
              const bytes = new Uint8Array(n);
              for (let i = 0; i < n; i++) bytes[i] = i % 251;
              globalThis.poTokenMinter = () => bytes;
              const out = await obtainPoToken(new Uint8Array([103, 75]));
              if (!(out instanceof Uint8Array)) throw new Error(n + ' not Uint8Array');
              if (out.length !== n) throw new Error(n + ' became ' + out.length);
              console.log('ACCEPTED ' + n + ' ' + out.join(','));
            }
            globalThis.poTokenMinter = () => new Uint8Array(0);
            try {
              await obtainPoToken(new Uint8Array([1]));
              throw new Error('empty token was accepted');
            } catch (e) {
              if (!String(e).includes('SZ:Empty') || !String(e).includes('type=Uint8Array')) throw e;
              console.log('REJECTED empty ' + e.message);
            }
            globalThis.poTokenMinter = () => 'not-bytes';
            try {
              await obtainPoToken(new Uint8Array([1]));
              throw new Error('non Uint8Array was accepted');
            } catch (e) {
              if (!String(e).includes('ODM:Invalid') || !String(e).includes('length=')) throw e;
              console.log('REJECTED type ' + e.message);
            }
            """.trimIndent(),
        )
        val html = File.createTempFile("potoken-page", ".html")
        html.writeText(PO_TOKEN_HTML)
        val process = ProcessBuilder("node", script.absolutePath, html.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        assertEquals(0, code, output)
        val accepted = output.lines().filter { it.startsWith("ACCEPTED ") }
        assertEquals(listOf(88, 529, 533, 534, 535), accepted.map { it.split(' ')[1].toInt() })
        assertTrue(output.contains("REJECTED empty"))
        assertTrue(output.contains("REJECTED type"))

        val phone = accepted.first { it.startsWith("ACCEPTED 533 ") }.substringAfter("ACCEPTED 533 ")
        val encoded = u8ToBase64(phone)
        assertEquals(712, encoded.length)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        val ours = PoTokenResult(playerRequestPoToken = encoded, streamingDataPoToken = encoded)
        val attached = InnerTubePoTokenResult(
            playerRequestToken = ours.playerRequestPoToken,
            streamingDataToken = ours.streamingDataPoToken,
            visitorData = "visitor",
        )
        assertEquals(encoded, attached.playerRequestToken)
        assertEquals(encoded, attached.streamingDataToken)
        assertTrue(attached.playerRequestToken.isNotBlank())
        script.delete()
        html.delete()
    }

    @Test
    fun oneMinterIsReusedForTheNextTrack() = runBlocking {
        var created = 0
        val generator = PoTokenGenerator(
            createMinter = {
                created++
                fakeMinter { id -> "minted:$id" }
            },
        )
        val umju = generator.getWebClientPoToken("gKtEoGH2Jvk", "visitor")
        val hagalo = generator.getWebClientPoToken("5_hMfTfR1L0", "visitor")
        assertEquals(1, created)
        assertEquals("minted:visitor", umju?.playerRequestPoToken)
        assertEquals("minted:visitor", hagalo?.playerRequestPoToken)
        assertEquals("minted:gKtEoGH2Jvk", umju?.streamingDataPoToken)
        assertEquals("minted:5_hMfTfR1L0", hagalo?.streamingDataPoToken)
    }

    @Test
    fun anEmptyMintDoesNotOpenAnotherWebView() = runBlocking {
        var created = 0
        val generator = PoTokenGenerator(
            createMinter = {
                created++
                fakeMinter { throw PoTokenException("SZ:Empty - token is 0 bytes, type=Uint8Array") }
            },
        )
        assertNull(generator.getWebClientPoToken("gKtEoGH2Jvk", "visitor"))
        assertEquals(1, created)
    }

    private fun fakeMinter(mint: (String) -> String) = object : PoTokenMinter {
        override val isExpired: Boolean = false
        override val isDead: Boolean = false
        override suspend fun generatePoToken(identifier: String): String = mint(identifier)
        override fun close() = Unit
    }
}
