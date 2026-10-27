package org.dpdns.zerodep.wire;

import java.util.function.Function;

/**
 * 接入方自己的值类型在线上怎么写、怎么读（{@link Wire#register}）：例如金额类型按 {@code decimal} 写、按 BigDecimal 读回，
 * 年月按普通文字写。注册后 {@link Wire#encode}、{@link Wire#text}、{@link Wire#parse} 都认它；
 * {@link Wire#decode} 不知道目标类型，还原成哪种 Java 类型由 {@link WireReader} 决定。
 *
 * @param type   Java 类型；子类也按它写
 * @param kind   线上的种类；为 null 时写成普通文字（不进 {@code $types}）
 * @param writer 值 → 线上的文字
 * @param reader 线上的文字 → 值（{@link Wire#parse} 用）；读不出时抛 IllegalArgumentException
 */
public record WireCodec<T>(Class<T> type, WireType kind, Function<? super T, String> writer, Function<String, ? extends T> reader) {
    /** 写一个值（调用方已经确认它是 type 的实例） */
    String write(Object value) { return writer.apply(type.cast(value)); }
}
