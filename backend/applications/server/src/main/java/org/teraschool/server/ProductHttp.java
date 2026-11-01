package org.teraschool.server;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.teraschool.product.api.*;

/** 课程与产品的 HTTP 适配：只做协议转换，授权、校验、版本与审计在应用服务内。 */
@RestController
@RequestMapping(produces = "application/json")
class ProductHttp {
  private final ProductQueries queries;
  private final ProductCommands commands;

  ProductHttp(ProductQueries queries, ProductCommands commands) {
    this.queries = queries;
    this.commands = commands;
  }

  @GetMapping("/api/product/capabilities")
  ProductCapabilities capabilities() {
    return queries.capabilities();
  }

  @GetMapping("/api/product/catalog")
  ProductCatalog catalog(@RequestParam UUID schoolId) {
    return queries.catalog(schoolId);
  }

  @GetMapping("/api/product/price-versions")
  List<PriceVersion> priceVersions(@RequestParam UUID productId) {
    return queries.priceVersions(productId);
  }

  /** 指定业务日期可售的价格快照；报名用它冻结商品与规则。 */
  @GetMapping("/api/product/sellable")
  List<SellablePrice> sellable(@RequestParam UUID schoolId, @RequestParam String on) {
    return queries.sellable(schoolId, on);
  }

  @PostMapping(value = "/api/product/curricula", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  Curriculum createCurriculum(@RequestBody CreateCurriculum command) {
    return commands.createCurriculum(command);
  }

  @PostMapping(value = "/api/product/curricula/{id}/update", consumes = "application/json")
  Curriculum updateCurriculum(@PathVariable UUID id, @RequestBody UpdateCurriculum command) {
    return commands.updateCurriculum(id, command);
  }

  @PostMapping(value = "/api/product/products", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  CourseProduct createProduct(@RequestBody CreateProduct command) {
    return commands.createProduct(command);
  }

  @PostMapping(value = "/api/product/products/{id}/update", consumes = "application/json")
  CourseProduct updateProduct(@PathVariable UUID id, @RequestBody UpdateProduct command) {
    return commands.updateProduct(id, command);
  }

  @PostMapping(value = "/api/product/price-versions", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  PriceVersion draftPriceVersion(@RequestBody DraftPriceVersion command) {
    return commands.draftPriceVersion(command);
  }

  @PostMapping(value = "/api/product/price-versions/{id}/update", consumes = "application/json")
  PriceVersion updatePriceVersion(@PathVariable UUID id, @RequestBody UpdatePriceVersion command) {
    return commands.updatePriceVersion(id, command);
  }

  /** 发布与停用使用独立权限 PRICE_PUBLISH，不与普通维护合并。 */
  @PostMapping(value = "/api/product/price-versions/{id}/publish", consumes = "application/json")
  PriceVersion publishPriceVersion(@PathVariable UUID id, @RequestBody ExpectedVersion command) {
    return commands.publishPriceVersion(id, command);
  }

  @PostMapping(value = "/api/product/price-versions/{id}/retire", consumes = "application/json")
  PriceVersion retirePriceVersion(@PathVariable UUID id, @RequestBody ExpectedVersion command) {
    return commands.retirePriceVersion(id, command);
  }
}
