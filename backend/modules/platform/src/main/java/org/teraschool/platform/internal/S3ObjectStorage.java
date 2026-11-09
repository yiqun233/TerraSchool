package org.teraschool.platform.internal;

import java.net.URI;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.teraschool.platform.api.ObjectStorage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** S3 兼容私有存储；桶与凭据只从受控配置读取，不对业务层暴露地址。 */
@Component
public class S3ObjectStorage implements ObjectStorage {
  private static final int MAX_OBJECT_BYTES = 5 * 1024 * 1024;

  private final Environment env;

  public S3ObjectStorage(Environment env) {
    this.env = env;
  }

  private String bucket() {
    return env.getRequiredProperty("teraschool.storage.bucket");
  }

  @Override
  public boolean configured() {
    return List.of("endpoint", "region", "bucket", "access-key", "secret-key").stream()
        .allMatch(
            key -> {
              String value = env.getProperty("teraschool.storage." + key);
              return value != null && !value.isBlank();
            });
  }

  private S3Client client() {
    return S3Client.builder()
        .endpointOverride(URI.create(env.getRequiredProperty("teraschool.storage.endpoint")))
        .overrideConfiguration(
            config ->
                config
                    .apiCallTimeout(java.time.Duration.ofSeconds(15))
                    .apiCallAttemptTimeout(java.time.Duration.ofSeconds(5)))
        .region(Region.of(env.getRequiredProperty("teraschool.storage.region")))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    env.getRequiredProperty("teraschool.storage.access-key"),
                    env.getRequiredProperty("teraschool.storage.secret-key"))))
        .serviceConfiguration(
            S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build())
        .build();
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("缺少对象键");
    if (content == null || content.length == 0 || content.length > MAX_OBJECT_BYTES)
      throw new IllegalArgumentException("对象内容为空或超过大小限制");
    try (var client = client()) {
      client.putObject(
          request ->
              request
                  .bucket(bucket())
                  .key(key)
                  .contentType(
                      contentType == null || contentType.isBlank()
                          ? "application/octet-stream"
                          : contentType),
          RequestBody.fromBytes(content));
    }
  }

  @Override
  public byte[] get(String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("缺少对象键");
    try (var client = client();
        var content = client.getObject(request -> request.bucket(bucket()).key(key))) {
      if (content.response().contentLength() > MAX_OBJECT_BYTES)
        throw new IllegalStateException("对象超出大小限制");
      byte[] bytes = content.readNBytes(MAX_OBJECT_BYTES + 1);
      if (bytes.length > MAX_OBJECT_BYTES) throw new IllegalStateException("对象超出大小限制");
      return bytes;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("对象读取失败", error);
    }
  }

  @Override
  public void delete(String key) {
    if (key == null || key.isBlank()) return;
    try (var client = client()) {
      client.deleteObject(request -> request.bucket(bucket()).key(key));
    }
  }
}
