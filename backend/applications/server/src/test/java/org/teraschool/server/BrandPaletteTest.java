package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.awt.Color;
import org.junit.jupiter.api.Test;

/** 品牌派生只依赖 seed：预设可读性、on-brand 选择与版本必须稳定。 */
class BrandPaletteTest {
  @Test
  void everyPresetPassesTheDocumentedContrastThresholds() {
    for (var preset : BrandPalette.PRESETS) {
      var palette = BrandPalette.derive(preset.seed());
      assertThat(palette.adjusted()).as(preset.name()).isFalse();
      assertThat(BrandPalette.contrast(Color.decode(palette.brand()), Color.WHITE))
          .as(preset.name() + " 主色")
          .isGreaterThanOrEqualTo(4.5);
      assertThat(BrandPalette.contrast(Color.decode(palette.brandLink()), Color.WHITE))
          .as(preset.name() + " 链接")
          .isGreaterThanOrEqualTo(4.5);
      // 主色上的文字必须与主色达到 4.5:1；on-brand 由对比度自动选择。
      assertThat(
              BrandPalette.contrast(Color.decode(palette.onBrand()), Color.decode(palette.brand())))
          .as(preset.name() + " on-brand")
          .isGreaterThanOrEqualTo(4.5);
      assertThat(palette.algoVersion()).isEqualTo(BrandPalette.ALGO_VERSION);
    }
  }

  @Test
  void rejectsNonHexAndKeepsLowercaseStable() {
    assertThatThrownBy(() -> BrandPalette.derive("red"))
        .isInstanceOf(IllegalArgumentException.class);
    var palette = BrandPalette.derive("#254C78");
    assertThat(palette.seed()).isEqualTo("#254c78");
    assertThat(palette.brand()).isEqualTo("#254c78");
    assertThat(palette.brandSoft()).matches("^#[0-9a-f]{6}$");
    assertThat(palette.brandLine()).matches("^#[0-9a-f]{6}$");
  }

  @Test
  void onlyTheSixDocumentedPresetsAreAcceptedByServiceRule() {
    for (var preset : BrandPalette.PRESETS)
      assertThat(BrandPalette.isPreset(preset.seed())).isTrue();
    assertThat(BrandPalette.isPreset("#c2410c")).isFalse();
  }
}
