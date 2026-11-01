package org.teraschool.platform.api;

import java.util.UUID;

/** 只由服务端入口建立，退出时恢复旧上下文，不采用外部请求头指定的身份或编号。 */
public final class RequestTrace {
  public enum Source {
    HTTP,
    APPLICATION
  }

  private record Context(UUID id, Source source) {}

  private static final ThreadLocal<Context> LOCAL = new ThreadLocal<>();

  private RequestTrace() {}

  public static Scope open(Source source) {
    var old = LOCAL.get();
    if (old == null) LOCAL.set(new Context(UUID.randomUUID(), source));
    return new Scope(old);
  }

  public static UUID id() {
    return LOCAL.get() == null ? null : LOCAL.get().id();
  }

  public static Source source() {
    return LOCAL.get().source();
  }

  public static final class Scope implements AutoCloseable {
    private final Context previous;

    private Scope(Context previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) LOCAL.remove();
      else LOCAL.set(previous);
    }
  }
}
