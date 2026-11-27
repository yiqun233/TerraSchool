package org.teraschool.product.api;

import java.util.List;
import java.util.UUID;

/**
 * 可售产品。
 *
 * <p>{@code publishedPrice} 是当前唯一已发布的价格版本；历史版本保留但不再是可售依据。
 *
 * <p>{@code oneOnOne} 为真时，报名审核通过后会自动建一对一班级并进班。 {@code curricula} 是产品包含的课程与课时；单课程产品只有一行。
 */
public record CourseProduct(
    UUID id,
    UUID schoolId,
    String schoolName,
    UUID curriculumId,
    String curriculumName,
    UUID groupId,
    String groupName,
    String name,
    int settlementMode,
    boolean oneOnOne,
    boolean active,
    long version,
    PriceVersion publishedPrice,
    List<ProductCurriculum> curricula) {
  public CourseProduct {
    curricula = curricula == null ? List.of() : List.copyOf(curricula);
  }
}
