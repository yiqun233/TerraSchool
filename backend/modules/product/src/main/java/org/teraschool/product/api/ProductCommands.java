package org.teraschool.product.api;

import java.util.UUID;

/** 课程、产品与价格版本的写入契约；授权、校验、版本与审计都在应用服务内完成。 */
public interface ProductCommands {
  Curriculum createCurriculum(CreateCurriculum command);

  Curriculum updateCurriculum(UUID id, UpdateCurriculum command);

  CourseProduct createProduct(CreateProduct command);

  CourseProduct updateProduct(UUID id, UpdateProduct command);

  PriceVersion draftPriceVersion(DraftPriceVersion command);

  PriceVersion updatePriceVersion(UUID id, UpdatePriceVersion command);

  /** 发布需要独立 PRICE_PUBLISH 权限；同一产品只保留一个已发布版本。 */
  PriceVersion publishPriceVersion(UUID id, ExpectedVersion command);

  PriceVersion retirePriceVersion(UUID id, ExpectedVersion command);
}
