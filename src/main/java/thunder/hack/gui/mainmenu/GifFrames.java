package thunder.hack.gui.mainmenu;

import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decodes an animated GIF into full, composited frames.
 *
 * <p>GIF frames are usually patches: each one covers only the part of the picture that changed, at an offset,
 * and says what to do with itself before the next one (keep it, clear it to the background, or restore what
 * was there). Reading the frames on their own gives flickering fragments, so every frame is drawn onto a
 * running canvas and a snapshot of the canvas is what gets kept.
 *
 * <p>Wallpapers can be big, and every frame becomes a GPU texture, so the result is held to a memory budget:
 * frames are scaled down, and if that is not enough every other frame is dropped (its delay folded into the
 * one kept, so the timing stays right).
 */
final class GifFrames {
    private GifFrames() {
    }

    record Result(List<BufferedImage> frames, int[] delaysMs) {
    }

    /** About 200MB of RGBA in total - the whole animation lives on the GPU while it is the wallpaper. */
    private static final long BUDGET_BYTES = 200L * 1024 * 1024;
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_FRAMES = 240;

    static Result decode(File file) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IllegalStateException("no gif reader");
            ImageReader reader = readers.next();
            reader.setInput(in, false);

            int count = Math.min(reader.getNumImages(true), MAX_FRAMES);
            if (count <= 0) throw new IllegalStateException("not an animated gif");
            int width = -1, height = -1;

            // logical screen size from the stream metadata, falling back to the first frame
            IIOMetadata stream = reader.getStreamMetadata();
            if (stream != null) {
                Node lsd = child(stream.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                if (lsd != null) {
                    width = intAttr(lsd, "logicalScreenWidth", -1);
                    height = intAttr(lsd, "logicalScreenHeight", -1);
                }
            }
            if (width <= 0 || height <= 0) {
                BufferedImage first = reader.read(0);
                width = first.getWidth();
                height = first.getHeight();
            }

            // fit the budget: first by scale, then by dropping frames
            double scale = Math.min(1.0, MAX_WIDTH / (double) width);
            long perFrame = (long) (width * scale) * (long) (height * scale) * 4;
            int stride = 1;
            while (perFrame * (count / stride) > BUDGET_BYTES && scale > 0.4) {
                scale *= 0.85;
                perFrame = (long) (width * scale) * (long) (height * scale) * 4;
            }
            while (perFrame * (count / stride) > BUDGET_BYTES) stride++;

            int outW = Math.max(1, (int) Math.round(width * scale)), outH = Math.max(1, (int) Math.round(height * scale));

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            int carried = 0;

            for (int i = 0; i < count; i++) {
                BufferedImage patch = reader.read(i);
                IIOMetadata meta = reader.getImageMetadata(i);
                Node root = meta.getAsTree("javax_imageio_gif_image_1.0");

                Node desc = child(root, "ImageDescriptor");
                int x = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
                int y = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);

                Node gce = child(root, "GraphicControlExtension");
                int delay = gce == null ? 10 : intAttr(gce, "delayTime", 10);
                String disposal = gce == null ? "none" : attr(gce, "disposalMethod", "none");
                // browsers treat 0-1 hundredths as "as fast as you can" and show ~100ms; do the same
                int delayMs = delay <= 1 ? 100 : delay * 10;

                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;

                Graphics2D g = canvas.createGraphics();
                g.drawImage(patch, x, y, null);
                g.dispose();

                carried += delayMs;
                if (i % stride == 0) {
                    frames.add(scaled(canvas, outW, outH));
                    delays.add(carried);
                    carried = 0;
                } else if (!delays.isEmpty()) {
                    delays.set(delays.size() - 1, delays.getLast() + delayMs);
                    carried = 0;
                }

                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D clear = canvas.createGraphics();
                    clear.setComposite(AlphaComposite.Clear);
                    clear.fillRect(x, y, patch.getWidth(), patch.getHeight());
                    clear.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }
            reader.dispose();

            int[] d = new int[delays.size()];
            for (int i = 0; i < d.length; i++) d[i] = Math.max(20, delays.get(i));
            return new Result(frames, d);
        }
    }

    private static BufferedImage scaled(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // nearest: these are mostly pixel art, and smoothing a downscale of pixel art just muddies it
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static Node child(Node node, String name) {
        if (node == null) return null;
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling())
            if (name.equals(c.getNodeName())) return c;
        return null;
    }

    private static String attr(Node node, String name, String fallback) {
        Node a = node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return a == null ? fallback : a.getNodeValue();
    }

    private static int intAttr(Node node, String name, int fallback) {
        try {
            return Integer.parseInt(attr(node, name, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
