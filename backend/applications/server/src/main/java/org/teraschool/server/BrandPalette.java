package org.teraschool.server;

import java.awt.Color;
import java.util.List;

/**
 * 品牌色派生：只由 seed 计算，界面、导出与打印共用同一份结果。
 *
 * <p>规则来自《多租户品牌主题方案》第 5.2 节：hover/active 降低明度， soft/line 保留色相饱和度提高明度，link 不足 4.5:1 时逐档压暗， on-brand
 * 在白与深灰中取对比度高者。算法版本变化时必须提升 {@link #ALGO_VERSION}， 读取时按版本重算，避免历史令牌与当前算法混用。
 */
final class BrandPalette {
  static final int ALGO_VERSION = 1;
  private static final Color WHITE = new Color(0xffffff);
  private static final Color INK = new Color(0x1f2937);

  record Preset(String name, String seed) {}

  static final List<Preset> PRESETS =
      List.of(
          new Preset("深蓝", "#254c78"),
          new Preset("靛蓝", "#3b4e9b"),
          new Preset("青", "#0f6e6e"),
          new Preset("墨绿", "#1f5b4a"),
          new Preset("石墨", "#3d4453"),
          new Preset("暖棕", "#6b4a2f"));

  record Palette(
      String seed,
      String brand,
      String brandHover,
      String brandActive,
      String brandSoft,
      String brandLine,
      String brandLink,
      String onBrand,
      boolean adjusted,
      int algoVersion) {}

  private BrandPalette() {}

  static boolean isPreset(String seed) {
    return PRESETS.stream().anyMatch(preset -> preset.seed().equalsIgnoreCase(seed));
  }

  static Palette derive(String seed) {
    if (seed == null || !seed.matches("^#[0-9A-Fa-f]{6}$"))
      throw new IllegalArgumentException("品牌色必须是 #RRGGBB");
    var normalized = seed.toLowerCase();
    var rgb = Color.decode(normalized);
    var hsl = Hsl.from(rgb);
    var brandLink = normalized;
    var adjusted = false;
    // 链接色单独保证白底 4.5:1；预设都能直接通过，自定义色不达标时逐档压暗。
    while (contrast(Color.decode(brandLink), WHITE) < 4.5 && hsl.lightness() > 0) {
      adjusted = true;
      var next = hsl.withLightness(Math.max(0, hsl.lightness() - 0.005));
      brandLink = next.toHex();
    }
    var onBrand = contrast(rgb, WHITE) >= contrast(rgb, INK) ? "#ffffff" : "#1f2937";
    return new Palette(
        normalized,
        normalized,
        hsl.withLightness(Math.max(0, hsl.lightness() - 0.08)).toHex(),
        hsl.withLightness(Math.max(0, hsl.lightness() - 0.14)).toHex(),
        hsl.withLightness(0.96).toHex(),
        hsl.withLightness(0.88).toHex(),
        brandLink,
        onBrand,
        adjusted,
        ALGO_VERSION);
  }

  static double contrast(Color first, Color second) {
    var high = Math.max(luminance(first), luminance(second));
    var low = Math.min(luminance(first), luminance(second));
    return (high + 0.05) / (low + 0.05);
  }

  private static double luminance(Color color) {
    return 0.2126 * channel(color.getRed())
        + 0.7152 * channel(color.getGreen())
        + 0.0722 * channel(color.getBlue());
  }

  private static double channel(int value) {
    var normalized = value / 255.0;
    return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
  }

  /** HSL 只用于派生；存储与接口仍使用 #RRGGBB，避免前端猜格式。 */
  private record Hsl(double hue, double saturation, double lightness) {
    static Hsl from(Color color) {
      var red = color.getRed() / 255.0;
      var green = color.getGreen() / 255.0;
      var blue = color.getBlue() / 255.0;
      var max = Math.max(red, Math.max(green, blue));
      var min = Math.min(red, Math.min(green, blue));
      var lightness = (max + min) / 2;
      if (Math.abs(max - min) < 1e-9) return new Hsl(0, 0, lightness);
      var delta = max - min;
      var saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min);
      double hue;
      if (max == red) hue = (green - blue) / delta + (green < blue ? 6 : 0);
      else if (max == green) hue = (blue - red) / delta + 2;
      else hue = (red - green) / delta + 4;
      return new Hsl(hue / 6, saturation, lightness);
    }

    Hsl withLightness(double value) {
      return new Hsl(hue, saturation, Math.max(0, Math.min(1, value)));
    }

    String toHex() {
      var rgb = toRgb();
      return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private int[] toRgb() {
      if (saturation == 0) {
        var value = (int) Math.round(lightness * 255);
        return new int[] {value, value, value};
      }
      var q =
          lightness < 0.5
              ? lightness * (1 + saturation)
              : lightness + saturation - lightness * saturation;
      var p = 2 * lightness - q;
      return new int[] {
        (int) Math.round(hueToRgb(p, q, hue + 1.0 / 3) * 255),
        (int) Math.round(hueToRgb(p, q, hue) * 255),
        (int) Math.round(hueToRgb(p, q, hue - 1.0 / 3) * 255)
      };
    }

    private static double hueToRgb(double p, double q, double t) {
      var value = t;
      if (value < 0) value += 1;
      if (value > 1) value -= 1;
      if (value < 1.0 / 6) return p + (q - p) * 6 * value;
      if (value < 1.0 / 2) return q;
      if (value < 2.0 / 3) return p + (q - p) * (2.0 / 3 - value) * 6;
      return p;
    }
  }
}
