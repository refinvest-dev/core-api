package com.refinvest.core.config.ownerquery

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Protected local parent directories are required; portable JDK checks cannot eliminate ancestor replacement races. */
internal object OwnerQuerySnapshotFile {
    const val MAX_BYTES = 65536

    fun read(path: Path, budget: PreparationBudget): ByteArray {
        try {
            budget.check()
            require(path.fileSystem == FileSystems.getDefault() && path.isAbsolute &&
                path == path.normalize() && !path.toString().startsWith("\\\\"))
            var ancestor = path.root
            for (component in path) {
                ancestor = ancestor.resolve(component)
                val attributes = Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink && !attributes.isOther)
            }
            val real = path.toRealPath()
            require(real == path.toRealPath(NOFOLLOW_LINKS))
            val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile)

            val bytes = Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                require(channel.size() in 1..MAX_BYTES.toLong())
                val output = ByteArrayOutputStream()
                val buffer = ByteBuffer.allocate(4096)
                while (true) {
                    budget.check()
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() + count <= MAX_BYTES)
                    output.write(buffer.array(), 0, count)
                }
                output.toByteArray().also { require(it.isNotEmpty()) }
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(after.isRegularFile && before.fileKey() == after.fileKey() &&
                before.creationTime() == after.creationTime() && before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() &&
                path.toRealPath() == real && path.toRealPath(NOFOLLOW_LINKS) == real)
            budget.check()
            return bytes
        } catch (error: OwnerQueryPreparationException) {
            throw OwnerQueryPreparationException(error.stage)
        } catch (_: Exception) {
            throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.INPUT)
        }
    }
}
