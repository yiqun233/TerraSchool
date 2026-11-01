package org.teraschool.organization.internal;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Component
class S3PrivateObjectStorage implements PrivateObjectStorage {
  private final Environment env;

  S3PrivateObjectStorage(Environment env) {
    this.env = env;
  }

  private String bucket() {
    return env.getRequiredProperty("teraschool.storage.bucket");
  }

  public boolean configured() {
    return java.util.List.of("endpoint", "region", "bucket", "access-key", "secret-key").stream()
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

  public void put(String key, byte[] content) {
    try (var client = client()) {
      client.putObject(
          request -> request.bucket(bucket()).key(key).contentType("application/octet-stream"),
          RequestBody.fromBytes(content));
    }
  }

  public byte[] get(String key) {
    try (var client = client();
        var content = client.getObject(request -> request.bucket(bucket()).key(key))) {
      if (content.response().contentLength() > 5242880) throw new IllegalStateException("附件超出大小限制");
      byte[] bytes = content.readNBytes(5242881);
      if (bytes.length > 5242880) throw new IllegalStateException("附件超出大小限制");
      return bytes;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("附件读取失败", error);
    }
  }

  public void delete(String key) {
    try (var client = client()) {
      client.deleteObject(request -> request.bucket(bucket()).key(key));
    }
  }
}
