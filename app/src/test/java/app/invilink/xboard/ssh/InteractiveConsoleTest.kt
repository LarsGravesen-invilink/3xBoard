package app.invilink.xboard.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.nio.charset.Charset
import app.invilink.xboard.ui.renderConsoleOutput
import org.junit.Assert.assertNotEquals

class InteractiveConsoleTest {
    @Test fun utf8SplitAcrossReadsIsDecodedIncrementally() = runBlocking {
        val expected = "Привет 🌍\u001b[31m!\u001b[0m"
        val bytes = expected.toByteArray(Charsets.UTF_8)
        val input = object : InputStream() {
            var index = 0
            override fun read(): Int = if (index == bytes.size) -1 else bytes[index++].toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index == bytes.size) return -1
                b[off] = bytes[index++]
                return 1
            }
        }
        val result = StringBuilder()
        readConsoleText(input, { reader, chars -> reader.read(chars) }, { result.append(it) })
        assertEquals(expected, result.toString())
    }

    @Test fun legacyEncodingsDecodeAndEncode() = runBlocking {
        val text = "Привет, мир!"
        for (name in listOf("windows-1251", "KOI8-R")) {
            val charset = Charset.forName(name)
            val bytes = text.toByteArray(charset)
            val result = StringBuilder()
            readConsoleText(bytes.inputStream(), { reader, chars -> reader.read(chars) }, { result.append(it) }, charset)
            assertEquals(text, result.toString())
            assertEquals(bytes.toList(), result.toString().toByteArray(charset).toList())
        }
    }

    @Test fun lineControlsAndSgrAreRendered() {
        val rendered = renderConsoleOutput("hello\rXY\b!\u001b[K\n\u001b[31mred\u001b[0m plain")
        assertEquals("X!\nred plain", rendered.text)
        assertNotEquals(rendered.spanStyles.first { it.start <= 3 && it.end > 3 }.item.color,
            rendered.spanStyles.first { it.start <= 7 && it.end > 7 }.item.color)
    }

    @Test fun incompleteAndUnsupportedSequencesDoNotLeakIntoText() {
        assertEquals("ab", renderConsoleOutput("a\u001b[2Jb\u001b[31").text)
        assertEquals("ok", renderConsoleOutput("\u001b]0;title\u0007ok").text)
    }

    @Test fun scrollbackRetainsBoundedTailAndReportsTruncation() {
        val output = ConsoleScrollback()
        output.append("x".repeat(CONSOLE_OUTPUT_LIMIT))
        org.junit.Assert.assertFalse(output.truncated)
        output.append("y")
        org.junit.Assert.assertTrue(output.truncated)
        assertEquals(CONSOLE_OUTPUT_LIMIT, output.toString().length)
        assertEquals('y', output.toString().last())
        output.append("z".repeat(CONSOLE_OUTPUT_LIMIT + 10))
        assertEquals("z".repeat(CONSOLE_OUTPUT_LIMIT), output.toString())
    }

    @Test fun scrollbackDoesNotSplitSurrogateAtCut() {
        val output = ConsoleScrollback()
        output.append("😀" + "x".repeat(CONSOLE_OUTPUT_LIMIT - 2))
        output.append("y")
        org.junit.Assert.assertTrue(output.truncated)
        assertEquals('x', output.toString().first())
    }

    @Test fun longInputAndInterruptPassWhileQueueIsFull() = runBlocking {
        val input = kotlinx.coroutines.channels.Channel<String>(CONSOLE_QUEUE_CAPACITY)
        val interrupts = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val text = "a".repeat(4095) + "😀" + "b".repeat(36_000)
        org.junit.Assert.assertTrue(input.trySend(text).isSuccess)
        org.junit.Assert.assertTrue(input.trySend("pending").isFailure)
        val result = StringBuilder()
        writeConsoleInput(input, interrupts) { chunk ->
            result.append(chunk)
            if (result.length == 4095) interrupts.trySend(Unit)
            if (result.length == text.length + 1) input.close()
        }
        assertEquals(text, result.toString().replace("\u0003", ""))
        assertEquals(4095, result.indexOf("\u0003"))
    }

    @Test fun outputIsNotTruncatedAtPreviousLimits() = runBlocking {
        val expected = "строка\n".repeat(25000)
        val result = StringBuilder()
        readConsoleText(expected.byteInputStream(), { reader, chars -> reader.read(chars) }, { result.append(it) })
        assertEquals(expected, result.toString())
    }
}
