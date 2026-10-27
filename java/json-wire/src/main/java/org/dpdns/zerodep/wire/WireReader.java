package org.dpdns.zerodep.wire;

/**
 * {@link Wire#decode} 把 {@code $types} 标出的文字还原成什么（同 TypeScript 的 {@code fromWire} 的选项）。
 * 默认是 {@link Wire#read}；要别的类型时包一层，只换自己关心的种类：
 * <pre>{@code
 * WireReader zoned = (kind, text) -> kind == WireType.TIMESTAMPTZ ? OffsetDateTime.parse(text) : Wire.read(kind, text);
 * }</pre>
 */
@FunctionalInterface
public interface WireReader {
    /** 读出一个值；读不出时抛 IllegalArgumentException */
    Object read(WireType kind, String text);
}
