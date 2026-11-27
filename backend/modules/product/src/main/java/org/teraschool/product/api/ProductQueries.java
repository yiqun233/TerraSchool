package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

public interface ProductQueries {
  ProductCapabilities capabilities();

  /** 按学校返回课程与产品；已发布价格随产品返回。 */
  ProductCatalog catalog(UUID schoolId);

  List<PriceVersion> priceVersions(UUID productId);

  /** 指定业务日期可售的价格快照，供报名冻结商品与规则；campusId 非空时按校区过滤。 */
  List<SellablePrice> sellable(UUID schoolId, String on, UUID campusId);

  /** 产品的可售校区；空列表表示全校区可售。 */
  List<UUID> productCampuses(UUID productId);

  /** 产品包含的课程与课时分配；单课程产品也返回一行。 */
  List<ProductCurriculum> productCurricula(UUID productId);
}
