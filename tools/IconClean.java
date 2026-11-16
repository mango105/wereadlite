import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 把 design/icon-source.png（用户选定的图标稿）清理成可直接用于 launcher 的多密度 PNG。
 *
 * 原稿是 236x229 的截图：四周带浅色底、一圈白色描边和一圈黑色外框线，底部圆角还被裁掉。
 * 这里只取内层黑底书本（SX/SY/SW/SH），用重新生成的圆角遮罩重做四边并留出透明边距，
 * 造型、线条、配色全部保留原样，不重画。
 *
 *   java tools/IconClean.java       # 在仓库根目录执行
 */
public class IconClean {

    // 内层黑色圆角方块的包围盒（从原稿逐行扫描暗像素得到）
    private static final int SX = 17, SY = 20, SW = 205, SH = 206;
    private static final double PAD = 0.045;   // 四周透明留白占比
    private static final double RAD = 0.175;  // 目标圆角半径，略小于原稿圆角，确保裁净白描边

    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File("design/icon-source.png"));
        System.out.println("source " + src.getWidth() + "x" + src.getHeight());
        int[][] probes = {{SX,SY},{118,SY},{SX+SW-1,SY},{SX,SY+SH-1},{118,SY+SH-1},
                          {SX+SW-1,SY+SH-1},{118,228},{10,114},{230,114}};
        for (int[] p : probes)
            System.out.printf("  (%3d,%3d)=#%06x%n", p[0], p[1], src.getRGB(p[0], p[1]) & 0xFFFFFF);

        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] sizes = {48, 72, 96, 144, 192};
        // 小尺寸若直接从 205px 原稿双线性缩小，1px 白线会混叠成灰点；
        // 统一先渲染 512 高分版，再按面积平均下采样，线条连续且边缘平滑。
        BufferedImage hi = render(src, 512);
        new File("design").mkdirs();
        ImageIO.write(quantize(hi), "png", new File("design/icon-main.png"));
        for (int i = 0; i < sizes.length; i++) {
            File f = new File("res/mipmap-" + dens[i] + "/ic_launcher.png");
            f.getParentFile().mkdirs();
            ImageIO.write(quantize(downsample(hi, sizes[i])), "png", f);
            System.out.println("  " + f.getPath() + "  " + sizes[i] + "x" + sizes[i]);
        }
    }

    /**
     * 抗锯齿会产生上千种近似色，PNG 压不动。黑白图标只需 16 级色阶，
     * 量化后肉眼无差、文件小一半以上。
     */
    private static BufferedImage quantize(BufferedImage im) {
        int w = im.getWidth();
        BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < w; y++) for (int x = 0; x < w; x++) {
            int c = im.getRGB(x, y), a = (c >>> 24) & 255;
            if (a == 0) { out.setRGB(x, y, 0); continue; }
            int r = q((c >> 16) & 255), g = q((c >> 8) & 255), b = q(c & 255);
            out.setRGB(x, y, (q(a) << 24) | (r << 16) | (g << 8) | b);
        }
        return out;
    }

    private static int q(int v) {
        return Math.min(255, ((v + 8) / 16) * 16);
    }

    /** 预乘 alpha 的 box 平均下采样。 */
    private static BufferedImage downsample(BufferedImage hi, int s) {
        int h = hi.getWidth();
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int y0 = y * h / s, y1 = Math.max(y0 + 1, (y + 1) * h / s);
                int x0 = x * h / s, x1 = Math.max(x0 + 1, (x + 1) * h / s);
                long sa = 0, sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) for (int xx = x0; xx < x1; xx++) {
                    int c = hi.getRGB(xx, yy), a = (c >>> 24) & 255;
                    sa += a;
                    sr += ((c >> 16) & 255) * a;
                    sg += ((c >> 8) & 255) * a;
                    sb += (c & 255) * a;
                    n++;
                }
                int a = (int) (sa / n);
                if (a == 0) { out.setRGB(x, y, 0); continue; }
                // sr/sg/sb 已是 Σ(rgb*a)，除以 Σa 直接得到未预乘的平均色
                int r = (int) Math.min(255, sr / sa);
                int g = (int) Math.min(255, sg / sa);
                int b = (int) Math.min(255, sb / sa);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static BufferedImage render(BufferedImage src, int s) {
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        double pad = PAD * s, len = s - 2 * pad, rad = RAD * s;
        double loX = pad + rad, hiX = pad + len - rad;
        double loY = pad + rad, hiY = pad + len - rad;

        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                // 3x3 子采样求圆角遮罩覆盖率，边缘抗锯齿
                int hit = 0;
                for (int sy = 0; sy < 3; sy++) for (int sx = 0; sx < 3; sx++) {
                    double px = x + (sx + 0.5) / 3.0, py = y + (sy + 0.5) / 3.0;
                    double cx = Math.max(loX, Math.min(px, hiX));
                    double cy = Math.max(loY, Math.min(py, hiY));
                    double dx = px - cx, dy = py - cy;
                    if (dx * dx + dy * dy <= rad * rad) hit++;
                }
                if (hit == 0) { out.setRGB(x, y, 0); continue; }

                // 目标底板内部 → 映射回原稿内层方块，双线性取色
                double u = Math.max(0, Math.min(1, (x + 0.5 - pad) / len));
                double v = Math.max(0, Math.min(1, (y + 0.5 - pad) / len));
                int rgb = sample(src, SX + u * SW, SY + v * SH);
                out.setRGB(x, y, (rgb & 0xFFFFFF) | ((hit * 255 / 9) << 24));
            }
        }
        return out;
    }

    private static int sample(BufferedImage im, double fx, double fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double ax = fx - x0, ay = fy - y0;
        int c00 = rgb(im, x0, y0), c10 = rgb(im, x0 + 1, y0);
        int c01 = rgb(im, x0, y0 + 1), c11 = rgb(im, x0 + 1, y0 + 1);
        int r = 0, g = 0, b = 0;
        for (int ch = 0; ch < 3; ch++) {
            int sh = 16 - 8 * ch;
            double v = ((c00 >> sh) & 255) * (1 - ax) * (1 - ay)
                    + ((c10 >> sh) & 255) * ax * (1 - ay)
                    + ((c01 >> sh) & 255) * (1 - ax) * ay
                    + ((c11 >> sh) & 255) * ax * ay;
            int iv = (int) Math.round(v);
            if (ch == 0) r = iv; else if (ch == 1) g = iv; else b = iv;
        }
        return (r << 16) | (g << 8) | b;
    }

    private static int rgb(BufferedImage im, int x, int y) {
        x = Math.max(0, Math.min(im.getWidth() - 1, x));
        y = Math.max(0, Math.min(im.getHeight() - 1, y));
        return im.getRGB(x, y);
    }
}
