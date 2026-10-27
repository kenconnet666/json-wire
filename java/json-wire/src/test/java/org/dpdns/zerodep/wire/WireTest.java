package org.dpdns.zerodep.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Line(String sku, BigDecimal qty) { }

    /** spec/order.json 里的对象；TypeScript 的 wire.test.ts 用普通对象写同一个。 */
    record Order(Long id, String code, int count, double rate, double ratio, boolean active, BigDecimal amount, LocalDate on, LocalTime at,
                 LocalDateTime created, Instant sent, byte[] file, String note, List<Line> lines, List<Long> tags, Map<String, BigDecimal> byMonth) { }

    private static Order order() {
        Map<String, BigDecimal> byMonth = new LinkedHashMap<>();
        byMonth.put("2024.01", new BigDecimal("3.5"));
        byMonth.put("2024.02", BigDecimal.ZERO);
        return new Order(9007199254740993L, "PO-1", 3, 0.25, Double.NaN, true, new BigDecimal("12.5"), LocalDate.of(2024, 1, 31),
            LocalTime.of(13, 45), LocalDateTime.of(2024, 1, 31, 13, 45, 0, 500_000_000), Instant.parse("2024-01-31T05:45:00Z"),
            new byte[] {1, 2, 3, (byte) 255}, null, List.of(new Line("A.1", new BigDecimal("2.75")), new Line("B", BigDecimal.ONE)),
            List.of(1L, 2L), byMonth);
    }

    /** 仓库根目录下的共用样例。 */
    private static JsonNode sample() throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(p.resolve("spec/order.json"))) p = p.getParent();
        return JSON.readTree(p.resolve("spec/order.json").toFile());
    }

    @Test
    void 编码结果经Jackson写出后与共用样例相同() throws Exception {
        assertEquals(sample(), JSON.readTree(JSON.writeValueAsString(Wire.encode(order()))));
    }

    @Test
    void 没有要还原的值时是普通的JSON树_根不是对象时包成value() {
        assertEquals(Map.of("name", "a", "n", 1), Wire.encode(Map.of("name", "a", "n", 1)));
        assertEquals(Map.of("$value", List.of("1", "2"), "$types", Map.of("$value", "bigint")), Wire.encode(List.of(1L, 2L)));
        assertEquals(Map.of("$value", "1000000000000000000000", "$types", Map.of("$value", "decimal")), Wire.encode(new BigDecimal("1E+21")));
    }

    /** 普通的类：公开 getter，布尔用 isX，字段名决定属性名的写法。 */
    public static final class Bean {
        private final String URL = "https://example.test";
        private final boolean open = true;
        private final Optional<OffsetDateTime> due = Optional.of(OffsetDateTime.of(2024, 1, 31, 13, 45, 0, 0, ZoneOffset.ofHours(8)));
        public final BigInteger big = BigInteger.TEN.pow(20);

        public String getURL() { return URL; }
        public boolean isOpen() { return open; }
        public Optional<OffsetDateTime> getDue() { return due; }
        public Thread.State getState() { return Thread.State.NEW; }
    }

    @Test
    void 普通的类取公开getter与公开字段() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("URL", "https://example.test");
        expected.put("open", true);
        expected.put("due", "2024-01-31T05:45:00Z");
        expected.put("big", "100000000000000000000");
        expected.put("state", "NEW");
        expected.put("$types", Map.of("due", "timestamptz", "big", "bigint"));
        assertEquals(expected, Wire.encode(new Bean()));
    }

    @Test
    void 同一路径只能是一种值_循环引用与保留的键报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> Wire.encode(List.of(Map.of("v", 1L), Map.of("v", "x"))));
        assertTrue(e.getMessage().contains("values at v are both bigint and text"), e.getMessage());
        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(loop));
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(Map.of("$types", Map.of())));
    }

    @Test
    void text与parse是地址栏里的单个值() {
        assertEquals("0.0000001", Wire.text(new BigDecimal("1E-7")));
        assertEquals("2024-01-31T05:45:00.12Z", Wire.text(Instant.parse("2024-01-31T05:45:00.120Z")));
        assertEquals("2024-01-31T13:45:00.5", Wire.text(LocalDateTime.of(2024, 1, 31, 13, 45, 0, 500_000_000)));
        assertEquals("0.25", Wire.text(0.25));
        assertEquals("", Wire.text(null));
        assertThrows(IllegalArgumentException.class, () -> Wire.text(List.of()));

        assertEquals(-12, Wire.parse(int.class, "-12"));
        assertEquals(9007199254740993L, Wire.parse(Long.class, "9007199254740993"));
        assertEquals(new BigDecimal("12.50"), Wire.parse(BigDecimal.class, "12.50"));
        assertEquals(LocalDateTime.of(2024, 1, 31, 13, 45), Wire.parse(LocalDateTime.class, "2024-01-31 13:45"));
        assertEquals(Instant.parse("2024-01-31T05:45:00Z"), Wire.parse(Instant.class, "2024-01-31T13:45:00+08:00"));
        assertEquals(Thread.State.NEW, Wire.parse(Thread.State.class, "NEW"));
        assertArrayEquals(new byte[] {1, 2, 3, (byte) 255}, Wire.parse(byte[].class, "AQID/w=="));
        for (String[] bad : new String[][] {{"Integer", "1.5"}, {"LocalDate", "2023-02-29"}, {"Boolean", "yes"}, {"BigDecimal", "NaN"}}) {
            Class<?> type = switch (bad[0]) {
                case "Integer" -> Integer.class;
                case "LocalDate" -> LocalDate.class;
                case "Boolean" -> Boolean.class;
                default -> BigDecimal.class;
            };
            var error = assertThrows(IllegalArgumentException.class, () -> Wire.parse(type, bad[1]));
            assertTrue(error.getMessage().contains("\"" + bad[1] + "\""), error.getMessage());
        }
    }

    @Test
    void 还原共用样例_值是对应的Java类型_再编码与样例相同() throws Exception {
        String text = JSON.writeValueAsString(sample());
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) Wire.decode(Json.read(text));
        assertEquals(9007199254740993L, order.get("id"));
        assertEquals(new BigDecimal("12.5"), order.get("amount"));
        assertEquals(LocalDate.of(2024, 1, 31), order.get("on"));
        assertEquals(LocalDateTime.of(2024, 1, 31, 13, 45, 0, 500_000_000), order.get("created"));
        assertEquals(Instant.parse("2024-01-31T05:45:00Z"), order.get("sent"));
        assertTrue(Double.isNaN((Double) order.get("ratio")));
        assertEquals(List.of(1L, 2L), order.get("tags"));
        assertEquals(new BigDecimal("2.75"), ((Map<?, ?>) ((List<?>) order.get("lines")).get(0)).get("qty"));
        assertEquals(new BigDecimal("3.5"), ((Map<?, ?>) order.get("byMonth")).get("2024.01"));
        assertEquals(sample(), JSON.readTree(JSON.writeValueAsString(Wire.encode(order))));
    }

    @Test
    void 还原时拆开value_没有types时原样返回_读不出时指出路径() {
        assertEquals(List.of(1L, 2L), Wire.decode(Wire.encode(List.of(1L, 2L))));
        Map<String, Object> plain = Map.of("a", "1");
        assertEquals(plain, Wire.decode(plain));
        var error = assertThrows(IllegalArgumentException.class,
            () -> Wire.decode(Map.of("rows", List.of(Map.of("on", "2026-02-30")), "$types", Map.of("rows.on", "date"))));
        assertTrue(error.getMessage().contains("rows.on"), error.getMessage());
    }

    /** 接入方自己的值类型 */
    record Money(BigDecimal amount) { }

    @Test
    void 注册的值类型按它的写法编码与读() {
        Wire.register(new WireCodec<>(Money.class, WireType.DECIMAL, m -> m.amount().toPlainString(), t -> new Money(new BigDecimal(t))));
        assertEquals(Map.of("price", "9.90", "$types", Map.of("price", "decimal")), Wire.encode(Map.of("price", new Money(new BigDecimal("9.90")))));
        assertEquals("9.90", Wire.text(new Money(new BigDecimal("9.90"))));
        assertEquals(new Money(new BigDecimal("9.90")), Wire.parse(Money.class, "9.90"));
    }

    /** 年月：按普通文字写，不进 $types。 */
    record Month(int year, int month) {
        @Override public String toString() { return String.format("%d-%02d", year, month); }

        static Month of(String text) {
            if (!text.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException("expected yyyy-MM");
            return new Month(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(5)));
        }
    }

    /** 某个接口的实现都按接口注册的写法写。 */
    interface Code { String value(); }

    record Sku(String value) implements Code { }

    @Test
    void 种类为null时写成普通文字_子类按父类型的写法写_parse要的是注册的类型本身() {
        Wire.register(new WireCodec<>(Month.class, null, Month::toString, Month::of));
        Wire.register(new WireCodec<>(Code.class, WireType.BIGINT, Code::value, t -> new Sku(t)));
        assertEquals(List.of(Map.of("m", "2024-01"), Map.of("m", "2024-02")), Wire.encode(List.of(Map.of("m", new Month(2024, 1)), Map.of("m", "2024-02"))));
        assertEquals(null, Wire.typeOf(new Month(2024, 1)));
        assertEquals(new Month(2024, 3), Wire.parse(Month.class, "2024-03"));
        var error = assertThrows(IllegalArgumentException.class, () -> Wire.parse(Month.class, "2024/03"));
        assertTrue(error.getMessage().contains("\"2024/03\" is not a Month"), error.getMessage());

        assertEquals(Map.of("sku", "42", "$types", Map.of("sku", "bigint")), Wire.encode(Map.of("sku", new Sku("42"))));
        assertEquals(WireType.BIGINT, Wire.typeOf(new Sku("42")));
        assertThrows(IllegalArgumentException.class, () -> Wire.parse(Sku.class, "42"));
    }

    @Test
    void 注册时校验参数() {
        assertThrows(NullPointerException.class, () -> Wire.register(null));
        assertThrows(NullPointerException.class, () -> new WireCodec<>(Month.class, null, Month::toString, null));
    }

    @Test
    void 还原成什么可以换() {
        WireReader offsets = (kind, text) -> kind == WireType.TIMESTAMPTZ ? OffsetDateTime.parse(text) : Wire.read(kind, text);
        Object at = ((Map<?, ?>) Wire.decode(Map.of("at", "2024-01-31T05:45:00Z", "$types", Map.of("at", "timestamptz")), offsets)).get("at");
        assertEquals(OffsetDateTime.of(2024, 1, 31, 5, 45, 0, 0, ZoneOffset.UTC), at);
    }

    @Test
    void reader可以把decimal读回注册的值类型_往返一致_读不出时指出路径() throws Exception {
        Wire.register(new WireCodec<>(Money.class, WireType.DECIMAL, m -> m.amount().toPlainString(), t -> new Money(new BigDecimal(t))));
        WireReader money = (kind, text) -> kind == WireType.DECIMAL ? Wire.parse(Money.class, text) : Wire.read(kind, text);
        String text = JSON.writeValueAsString(Wire.encode(Map.of("lines", List.of(Map.of("price", new Money(new BigDecimal("2.75")))))));
        Map<?, ?> line = (Map<?, ?>) ((List<?>) ((Map<?, ?>) Wire.decode(Json.read(text), money)).get("lines")).get(0);
        assertEquals(new Money(new BigDecimal("2.75")), line.get("price"));

        WireReader refuse = (kind, text2) -> { throw new IllegalArgumentException("nope"); };
        var error = assertThrows(IllegalArgumentException.class, () -> Wire.decode(Map.of("a", Map.of("b", "1"), "$types", Map.of("a.b", "bigint")), refuse));
        assertTrue(error.getMessage().contains("Wire.decode: a.b: nope"), error.getMessage());
    }

    @Test
    void toJson与fromJson是typed_JSON文字的往返() throws Exception {
        String text = Wire.toJson(order());
        assertEquals(sample(), JSON.readTree(text));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Wire.fromJson(text);
        assertEquals(9007199254740993L, back.get("id"));
        assertEquals(LocalDate.of(2024, 1, 31), back.get("on"));
        assertEquals("{\"a\":1}", Wire.toJson(Map.of("a", 1)));
        assertEquals(null, Wire.fromJson(null));
        assertEquals(List.of(1L, 2L), Wire.fromJson(Wire.toJson(List.of(1L, 2L))));
    }
}
