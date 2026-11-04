import com.ultracam.engine.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Desktop harness: runs the exact app engine on frames written by simulate_burst.py. */
public class RunPipeline {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        int n = Integer.parseInt(a[1]);
        Params p = new Params();
        p.captureEv = -1f;
        p.night = a.length > 2 && a[2].equals("night");
        p.sharpen = Float.parseFloat(System.getProperty("sharp", "" + p.sharpen));
        p.clarity = Float.parseFloat(System.getProperty("clar", "" + p.clarity));
        p.noiseFree = Float.parseFloat(System.getProperty("nf", "" + p.noiseFree));
        if (a.length > 3) p.rotation = Integer.parseInt(a[3]);
        FrameSource src = new FrameSource() {
            public int count() { return n; }
            public YuvFrame load(int i) throws java.io.IOException { return YuvFrame.read(new File(dir, "frame_" + i + ".yuv")); }
        };
        Pipeline.Result r = Pipeline.run(src, p, (s, f) -> System.out.printf("[%3.0f%%] %s%n", f * 100, s));
        save(r.argb, r.width, r.height, new File(dir, "result.png"));
        save(r.before, r.beforeW, r.beforeH, new File(dir, "before.png"));
        DumpDepth.dump(dir);
        System.out.println(r.report);
        System.out.println("output " + r.width + "x" + r.height);
    }
    static void save(int[] px, int w, int h, File f) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        im.setRGB(0, 0, w, h, px, 0, w);
        ImageIO.write(im, "png", f);
    }
}
class DumpDepth {
    static void dump(java.io.File dir) throws Exception {
        com.ultracam.engine.Depth.Map d = com.ultracam.engine.Finisher.lastDepth;
        int[] a = new int[d.w * d.h], b = new int[d.w * d.h];
        for (int i = 0; i < a.length; i++) {
            int s = (int) (d.subject[i] * 255), f = (int) (d.defocus[i] * 255);
            a[i] = 0xFF000000 | s << 16 | s << 8 | s; b[i] = 0xFF000000 | f << 16 | f << 8 | f;
        }
        RunPipeline.save(a, d.w, d.h, new java.io.File(dir, "subject.png"));
        RunPipeline.save(b, d.w, d.h, new java.io.File(dir, "defocus.png"));
    }
}
