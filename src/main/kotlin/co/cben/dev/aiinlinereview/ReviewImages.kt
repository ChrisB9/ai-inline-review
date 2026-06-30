package co.cben.dev.aiinlinereview

import com.intellij.openapi.project.Project
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Stores images pasted/inserted into a note under .claude/review/assets and returns the
 * project-relative path to reference in the markdown.
 */
object ReviewImages {

    private fun assetsDir(project: Project): Path {
        val base = project.basePath ?: error("Project has no base path")
        val dir = Paths.get(base, ".claude", "review", "assets")
        Files.createDirectories(dir)
        return dir
    }

    fun saveFromFile(project: Project, file: File): String {
        val ext = file.extension.ifBlank { "png" }
        val name = "${UUID.randomUUID()}.$ext"
        Files.copy(file.toPath(), assetsDir(project).resolve(name))
        return ".claude/review/assets/$name"
    }

    fun saveFromClipboard(project: Project): String? {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
        val name = "${UUID.randomUUID()}.png"
        ImageIO.write(toBuffered(image), "png", assetsDir(project).resolve(name).toFile())
        return ".claude/review/assets/$name"
    }

    private fun toBuffered(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return buffered
    }
}
