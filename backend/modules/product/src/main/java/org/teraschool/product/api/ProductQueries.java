package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

public interface ProductQueries {
  ProductCapabilities capabilities();

  /** 按学校返回课程与产品；已发布价格随产品返回。 */
  ProductCatalog catalog(UUID schoolId);

  List<PriceVersion> priceVersions(UUID productId);

  /** 指定业务日期可售的价格快照，供报名冻结商品与规则。 */
  List<SellablePrice> sellable(UUID schoolId, String on);
}
