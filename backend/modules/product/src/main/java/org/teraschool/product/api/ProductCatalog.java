package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

public record ProductCatalog(
    UUID schoolId, List<Curriculum> curricula, List<CourseProduct> products) {
  public ProductCatalog {
    curricula = List.copyOf(curricula);
    products = List.copyOf(products);
  }
}
