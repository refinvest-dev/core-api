package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope

import com.refinvest.core.tradingreview.domain.valueobject.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LocalTradingArtifactWorkspacesTest {
    private val scope = TradingArtifactScope.RawSlot(TradingImportSessionId(1), TradingArtifactRole.TRADE_HISTORY)

    @Test
    fun activeWritersDrainBeforeAbsenceAndLateWritesCannotRecreateTheScope(@TempDir root: Path) {
        val one = LocalTradingArtifactWorkspaces(root)
        val two = LocalTradingArtifactWorkspaces(root)
        val first = one.acquire(scope)
        val second = two.acquire(scope)
        val file = first.createTemporary()
        file.openOutput().use { it.write("synthetic".toByteArray()) }
        val unaffected = TradingArtifactScope.RawSlot(TradingImportSessionId(2), TradingArtifactRole.TRADE_HISTORY)
        val other = two.acquire(unaffected)
        val retained = other.createTemporary()
        retained.openOutput().use { it.write("retained".toByteArray()) }
        assertFalse(two.purge(scope))
        assertEquals("RESOURCE_DELETED", assertFailsWith<TradingFailure> { one.acquire(scope) }.code)
        assertFailsWith<TradingFailure> { first.checkActive() }
        first.close()
        assertFalse(two.purge(scope))
        second.close()
        assertTrue(two.purge(scope))
        assertFalse(Files.exists(file.path))
        assertEquals("retained", retained.openInput().bufferedReader().use { it.readText() })
        retained.close(); other.close()
    }

    @Test
    fun restartedAdapterDeletesAnOrphanUsingTheSameScope(@TempDir root: Path) {
        val first = LocalTradingArtifactWorkspaces(root)
        val lease = first.acquire(scope)
        val orphan = lease.createTemporary()
        orphan.openOutput().use { it.write(byteArrayOf(1,2,3)) }
        lease.close()
        assertTrue(Files.exists(orphan.path))
        assertTrue(LocalTradingArtifactWorkspaces(root).purge(scope))
        assertFalse(Files.exists(orphan.path))
        assertFailsWith<TradingFailure> { LocalTradingArtifactWorkspaces(root).acquire(scope) }
    }

    @Test
    @Timeout(40)
    fun anotherJvmHoldingTheScopeLockPreventsFalseAbsence(@TempDir root: Path) {
        val directory = root.resolve("workspace")
        val workspace = LocalTradingArtifactWorkspaces(directory)
        workspace.acquire(scope).close()
        val lockPath = Files.list(directory.resolve("control")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".lock") }.findFirst().orElseThrow()
        }
        val source = root.resolve("WorkspaceHolder.java")
        Files.writeString(source, """
            import java.nio.channels.*;
            import java.nio.file.*;
            public class WorkspaceHolder {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ,
                              StandardOpenOption.WRITE); var lock = channel.lock(0, Long.MAX_VALUE, true)) {
                        System.out.println("READY");
                        System.out.flush();
                        System.in.read();
                    }
                }
            }
        """.trimIndent())
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val child = ProcessBuilder(java.toString(), source.toString(), lockPath.toString()).redirectErrorStream(true).start()
        val readers = Executors.newSingleThreadExecutor()
        try {
            val ready = readers.submit<String> {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.firstOrNull { it == "READY" } ?: error("Child did not acquire the lock")
                }
            }
            assertEquals("READY", ready.get(20, TimeUnit.SECONDS))
            assertFalse(workspace.purge(scope))
            child.outputStream.use { it.write(1); it.flush() }
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            assertTrue(workspace.purge(scope))
        } finally {
            if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
            readers.shutdownNow()
        }
    }
}
