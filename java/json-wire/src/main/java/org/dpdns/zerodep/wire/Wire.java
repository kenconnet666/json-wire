package org.dpdns.zerodep.wire;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 线上的 typed JSON：Long、BigInteger、BigDecimal、java.time 的值、字节等 JSON 不能原样承载（或 JavaScript 读了会丢精度）的值
 * 写成文字，根对象上的 {@code $types} 记下它们的位置，对方据此还原（种类见 {@link WireType}）。任何接口的请求、响应、消息都可以用；
 * TypeScript 的 {@code @zerodep-dev/json-wire} 是同一套写法，两边的测试对照仓库里同一份样例 {@code spec/order.json}。
 *
 * <pre>{@code
 * @GetMapping("/orders/{id}")
 * Object order(@PathVariable long id) { return Wire.encode(service.find(id)); }   // Jackson、Gson 都能原样写出
 * }</pre>
 *
 * 编码的结果只由 Map、List、String、Number、Boolean 和 null 组成，所以不依赖哪一个 JSON 库，也不受它的配置影响。
 * 读：绑定到有类型的对象时 Spring 的 Jackson 本来就把 {@code "12.50"}、ISO 日期读成 BigDecimal、LocalDate（忽略 {@code $types}）；
 * 没有类型可依的树（表单的值、{@code Map<String, Object>}）用 {@link #decode} 按 {@code $types} 还原；地址栏里的单个值用 {@link #parse}。
 * <p>扩展：接入方自己的值类型用 {@link #register} 注册怎么写、怎么读（{@link WireCodec}）；{@link #decode} 还原成什么可以换（{@link WireReader}）。
 * 除注册表外没有可变的状态，各个方法都可以并发调用。
 */
public final class Wire {
    /** 根对象上的类型表。 */
    public static final String TYPES = "$types";

    /** 根不是对象（列表、单个值）又有要还原的值时，包成 {@code {"$value": 根, "$types": …}}。 */
    public static final String VALUE = "$value";

    /** 时间写到秒，秒以下的部分去掉末尾的零。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_TIME;

    /** 日期时间写成 'yyyy-MM-ddTHH:mm:ss[.fraction]'。 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 带时差的时刻换算成 UTC 后写成 'yyyy-MM-ddTHH:mm:ss[.fraction]Z'。 */
    private static final DateTimeFormatter TIMESTAMPTZ = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** 注册的值类型，后注册的在前（同一个类型后注册的盖过先注册的）。 */
    private static final List<WireCodec<?>> CODECS = new CopyOnWriteArrayList<>();

    /** 工具类，不能实例化。 */
    private Wire() { }

    /**
     * 注册接入方自己的值类型：之后 {@link #encode}、{@link #typeOf}、{@link #text}、{@link #parse} 都按它写读，也盖过内置的写法。
     * 注册表是整个 JVM 共用的，约定在应用启动时、开始处理请求之前注册一次：注册本身线程安全，
     * 但与之并发的编码看不看得到这次注册没有保证。同一个类型注册多次时后注册的算数；值是好几个注册类型的实例时也是后注册的优先。
     */
    public static void register(WireCodec<?> codec) { CODECS.add(0, Objects.requireNonNull(codec, "codec")); }

    /** value 是某个注册类型的实例时是那个写法，否则为 null。 */
    private static WireCodec<?> codecOf(Object value) {
        for (WireCodec<?> codec : CODECS) if (codec.type().isInstance(value)) return codec;
        return null;
    }

    /** 恰好为 type 注册的写法，否则为 null（读的时候要的是这个类型本身，不是它的父类）。 */
    private static WireCodec<?> codecFor(Class<?> type) {
        for (WireCodec<?> codec : CODECS) if (codec.type() == type) return codec;
        return null;
    }

    /**
     * 把值写成线上的 JSON 树：要还原的值写成文字，根对象上加 {@code $types}；没有这样的值时就是普通的 JSON 树。
     * record 取组件，其余的类取公开的 getter（{@code getX}、布尔的 {@code isX}）和公开字段，Map 的键写成文字，
     * 集合与数组写成列表，枚举写成名字，Optional 取里面的值。同一路径上的值必须是同一种，循环引用报错。
     *
     * @return 由 Map、List、String、Number、Boolean 和 null 组成的树
     */
    public static Object encode(Object value) {
        if (value instanceof Map<?, ?> m && (m.containsKey(TYPES) || m.containsKey(VALUE))) {
            throw new IllegalArgumentException("Wire.encode: " + TYPES + " and " + VALUE + " are reserved on the root object");
        }
        Encoder encoder = new Encoder();
        Object json = encoder.write(value, "");
        Map<String, Object> types = new LinkedHashMap<>();
        encoder.found.forEach((path, type) -> { if (type != null) types.put(path, type.wireName()); });
        if (types.isEmpty()) return json;
        if (json instanceof Map<?, ?> object) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) object;
            root.put(TYPES, types);
            return root;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        types.forEach((path, type) -> wrapped.put(path.isEmpty() ? VALUE : VALUE + "." + path, type));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(VALUE, json);
        root.put(TYPES, wrapped);
        return root;
    }

    /** 值要还原成哪一种；JSON 能原样承载的值（文字、布尔、Integer、有限的 Double、null）为 null。 */
    public static WireType typeOf(Object value) {
        WireCodec<?> codec = codecOf(value);
        if (codec != null) return codec.kind();
        if (value instanceof Long || value instanceof BigInteger) return WireType.BIGINT;
        if (value instanceof BigDecimal) return WireType.DECIMAL;
        if (value instanceof Double d) return d.isNaN() || d.isInfinite() ? WireType.DOUBLE : null;
        if (value instanceof Float f) return f.isNaN() || f.isInfinite() ? WireType.DOUBLE : null;
        if (value instanceof java.sql.Date || value instanceof LocalDate) return WireType.DATE;
        if (value instanceof java.sql.Time || value instanceof LocalTime) return WireType.TIME;
        if (value instanceof java.sql.Timestamp || value instanceof LocalDateTime) return WireType.TIMESTAMP;
        if (value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof Instant || value instanceof java.util.Date) {
            return WireType.TIMESTAMPTZ;
        }
        if (value instanceof byte[]) return WireType.BYTES;
        return null;
    }

    /**
     * 一个值写成线上的文字：地址栏参数、表单字段都用它。BigDecimal 不用科学计数法，日期与时间是 ISO 写法，
     * 带时差的时刻换算成 UTC（{@code Z}），字节是 base64，枚举是名字；null 是空字符串。集合、对象等不是单个值的报错。
     */
    public static String text(Object value) {
        if (value == null) return "";
        String text = singleText(value);
        if (text == null) throw new IllegalArgumentException("Wire.text: " + value.getClass().getSimpleName() + " is not a single value");
        return text;
    }

    /** 单个值写成的文字；value 不是单个值（集合、对象）时为 null。 */
    private static String singleText(Object value) {
        WireCodec<?> codec = codecOf(value);
        if (codec != null) return codec.write(value);
        if (value instanceof CharSequence || value instanceof Character || value instanceof Boolean || value instanceof UUID) return value.toString();
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() : value.toString();
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return value.toString();
        WireType type = typeOf(value);
        return type == null ? null : special(type, value);
    }

    /** 要还原的值写成的文字；type 是 {@link #typeOf} 的结果。 */
    private static String special(WireType type, Object value) {
        return switch (type) {
            case BIGINT, DOUBLE -> value.toString();
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case DATE -> (value instanceof java.sql.Date d ? d.toLocalDate() : value).toString();
            case TIME -> TIME.format(value instanceof java.sql.Time t ? t.toLocalTime() : (LocalTime) value);
            case TIMESTAMP -> TIMESTAMP.format(value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value);
            case TIMESTAMPTZ -> TIMESTAMPTZ.format(instant(value).atOffset(ZoneOffset.UTC));
            case BYTES -> Base64.getEncoder().encodeToString((byte[]) value);
        };
    }

    /** 带时差的时刻对应的瞬间。 */
    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof ZonedDateTime z) return z.toInstant();
        if (value instanceof Instant i) return i;
        return ((java.util.Date) value).toInstant();
    }

    /**
     * 按 Java 类读线上的文字（地址栏参数、表头筛选的值），格式与 {@link #text} 写出的一致：整数不能有小数部分，
     * 日期要是真实存在的日历日期，日期时间也接受空格分隔，带时差的时刻要带 {@code Z} 或时差，枚举按名字。读不出时报错，消息里带上原文。
     */
    @SuppressWarnings("unchecked")
    public static <T> T parse(Class<T> type, String text) {
        if (text == null) throw new IllegalArgumentException("Wire.parse: text is null");
        Function<String, ?> reader = readerFor(box(type));
        if (reader == null) throw new IllegalArgumentException("Wire.parse: cannot read text as " + type.getSimpleName());
        try {
            return (T) reader.apply(text);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a " + type.getSimpleName(), e);
        }
    }

    /** 读 t 的方式；读不了这个类型时为 null。数字、Base64、枚举读不出时抛的都是 IllegalArgumentException。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Function<String, ?> readerFor(Class<?> t) {
        WireCodec<?> codec = codecFor(t);
        if (codec != null) return codec.reader();
        if (t == String.class) return text -> text;
        if (t == Integer.class) return Integer::valueOf;
        if (t == Long.class) return Long::valueOf;
        if (t == Short.class) return Short::valueOf;
        if (t == Byte.class) return Byte::valueOf;
        if (t == BigInteger.class) return BigInteger::new;
        if (t == BigDecimal.class) return BigDecimal::new;
        if (t == Double.class) return Double::valueOf;
        if (t == Float.class) return Float::valueOf;
        if (t == Boolean.class) return Wire::bool;
        if (t == LocalDate.class) return LocalDate::parse;
        if (t == LocalTime.class) return LocalTime::parse;
        if (t == LocalDateTime.class) return text -> LocalDateTime.parse(text.replaceFirst(" ", "T"));
        if (t == OffsetDateTime.class) return OffsetDateTime::parse;
        if (t == Instant.class) return text -> OffsetDateTime.parse(text).toInstant();
        if (t == ZonedDateTime.class) return ZonedDateTime::parse;
        if (t == UUID.class) return UUID::fromString;
        if (t == byte[].class) return Base64.getDecoder()::decode;
        if (t.isEnum()) return text -> Enum.valueOf((Class) t, text);
        return null;
    }

    /**
     * 把一个值转成 type（读请求体、表单里 decode 出来的值用）：null 是 null，已是 type 的原样返回；其余的值先写成线上的文字
     * （{@link #text}）再按 {@link #parse} 读，所以规则与 parse 一样严格：整数类型不接受有小数部分的数、超出范围的数，
     * 数与文字、带时差的时刻之间可以互转，注册的值类型也认。转不了时报 IllegalArgumentException，消息形如 {@code "12.5" is not a Long}。
     */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Class<T> type) {
        if (type == null) throw new IllegalArgumentException("Wire.convert: type is null");
        if (value == null) return null;
        if (box(type).isInstance(value)) return (T) value;
        if (readerFor(box(type)) == null) throw new IllegalArgumentException("Wire.convert: cannot convert to " + type.getSimpleName());
        // 12.50 的小数部分是零，转成整数类型时不算小数；转成文字时保留原样
        String text = value instanceof BigDecimal d && type != String.class ? d.stripTrailingZeros().toPlainString() : singleText(value);
        if (text == null) throw new IllegalArgumentException(value.getClass().getSimpleName() + " is not a " + type.getSimpleName());
        return parse(type, text);
    }

    /** 把列表的每一项转成 type（见 {@link #convert}）：null 是空列表，单个值不当成列表；报错时带上第几项。返回的列表不可修改。 */
    public static <T> List<T> convertList(Object value, Class<T> type) {
        if (type == null) throw new IllegalArgumentException("Wire.convertList: type is null");
        if (value == null) return List.of();
        if (!(value instanceof Collection<?> items)) {
            throw new IllegalArgumentException(value.getClass().getSimpleName() + " is not a list of " + type.getSimpleName());
        }
        List<T> out = new ArrayList<>(items.size());
        for (Object item : items) {
            try {
                out.add(convert(item, type));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("item " + out.size() + ": " + e.getMessage(), e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 读线上的 JSON 树（{@link Json#read} 或任何 JSON 库读出的 Map、List），与 TypeScript 的 {@code fromWire} 得到同样的值：
     * <ul>
     *   <li>根上 {@code $types} 标出的文字还原成 Long、BigDecimal、LocalDate 等（见 {@link #read}），去掉 {@code $types}，
     *       {@code { $value, $types }} 拆成里面的值；文字读不出时报错并指出路径；</li>
     *   <li>没标类型的数是 JavaScript 的数：放得进 int 的整数是 Integer，其余是 Double（JSON 库把它们读成 Long、BigDecimal，
     *       原样再编码就成了 bigint、decimal）。</li>
     * </ul>
     * 不改传入的树，返回新的（Map、List 不可修改）。
     */
    public static Object decode(Object json) { return decode(json, Wire::read); }

    /** 同上，按 reader 还原（例如 timestamptz 要 OffsetDateTime 而不是 Instant）。 */
    public static Object decode(Object json, WireReader reader) {
        if (!(json instanceof Map<?, ?> root) || !root.containsKey(TYPES)) return revive(json, "", Map.of(), reader);
        if (!(root.get(TYPES) instanceof Map<?, ?> table)) throw new IllegalArgumentException("Wire.decode: " + TYPES + " must be an object of path → type");
        Map<String, WireType> types = new LinkedHashMap<>();
        table.forEach((path, type) -> types.put(String.valueOf(path), kind(String.valueOf(type))));
        Map<String, Object> out = new LinkedHashMap<>();
        root.forEach((k, v) -> { if (!TYPES.equals(k)) out.put(String.valueOf(k), revive(v, Encoder.join("", String.valueOf(k)), types, reader)); });
        return out.size() == 1 && out.containsKey(VALUE) ? out.get(VALUE) : Collections.unmodifiableMap(out);
    }

    /** 写成 typed JSON 文字（存进库里的 JSON 列、幂等记录）：{@code Json.write(Wire.encode(value))}。与 TypeScript 的 {@code stringifyWire} 对等。 */
    public static String toJson(Object value) { return Json.write(encode(value)); }

    /** 读 typed JSON 文字：{@code Wire.decode(Json.read(text))}，text 为 null 时是 null。与 TypeScript 的 {@code parseWireJson} 对等。 */
    public static Object fromJson(String text) { return text == null ? null : decode(Json.read(text)); }

    /** 沿树往下，路径在 types 里的文字换成值；数组不占一段。 */
    private static Object revive(Object value, String path, Map<String, WireType> types, WireReader reader) {
        if (value instanceof String text) {
            WireType kind = types.get(path);
            if (kind == null) return text;
            try {
                return reader.read(kind, text);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Wire.decode: " + path + ": " + e.getMessage(), e);
            }
        }
        if (value instanceof List<?> items) {
            List<Object> out = new ArrayList<>(items.size());
            for (Object item : items) out.add(revive(item, path, types, reader));
            return Collections.unmodifiableList(out);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), revive(v, Encoder.join(path, String.valueOf(k)), types, reader)));
            return Collections.unmodifiableMap(out);
        }
        if (value instanceof Number n) return jsNumber(n);
        return value;
    }

    /** 没标类型的数按 JavaScript 的数：整数且放得进 int 时是 Integer，其余是 Double。 */
    private static Number jsNumber(Number n) {
        if (n instanceof Integer || n instanceof Double) return n;
        BigDecimal exact = n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
        if (exact.signum() == 0) return 0;
        if (exact.stripTrailingZeros().scale() <= 0 && exact.toBigInteger().bitLength() < 32) return exact.intValue();
        return exact.doubleValue();
    }

    /** {@code $types} 里的种类名。 */
    private static WireType kind(String name) {
        for (WireType t : WireType.values()) if (t.wireName().equals(name)) return t;
        throw new IllegalArgumentException("Wire.decode: unknown type " + name);
    }

    /**
     * {@link #decode} 默认的还原：bigint → Long（超出 long 时 BigInteger），decimal → BigDecimal，double → Double，
     * date / time / timestamp → LocalDate / LocalTime / LocalDateTime，timestamptz → Instant，bytes → byte[]。格式同 {@link #parse}。
     */
    public static Object read(WireType kind, String text) {
        return switch (kind) {
            case BIGINT -> {
                BigInteger n = parse(BigInteger.class, text);
                yield n.bitLength() < 64 ? (Object) n.longValue() : n;
            }
            case DECIMAL -> parse(BigDecimal.class, text);
            case DOUBLE -> parse(Double.class, text);
            case DATE -> parse(LocalDate.class, text);
            case TIME -> parse(LocalTime.class, text);
            case TIMESTAMP -> parse(LocalDateTime.class, text);
            case TIMESTAMPTZ -> parse(Instant.class, text);
            case BYTES -> parse(byte[].class, text);
        };
    }

    /** 只认 true 与 false。 */
    private static Boolean bool(String text) {
        if (text.equals("true")) return Boolean.TRUE;
        if (text.equals("false")) return Boolean.FALSE;
        throw new IllegalArgumentException("\"" + text + "\" is not true or false");
    }

    /** 基本类型装箱为对应的包装类。 */
    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        return type;
    }

    /** 一次编码：沿路径写下各个值，记下每个路径上值的种类（null 表示文字）。 */
    private static final class Encoder {
        /** 路径 → 种类；文字记为 null，用来发现同一路径既有文字又有要还原的值。 */
        final Map<String, WireType> found = new LinkedHashMap<>();
        /** 正在写的对象，发现循环引用。 */
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

        /** 写下 value，path 是它的路径（属性名里的 ~ 与 . 写成 ~0、~1；列表不占一段）。 */
        Object write(Object value, String path) {
            if (value == null) return null;
            if (value instanceof Optional<?> o) return write(o.orElse(null), path);
            WireCodec<?> codec = codecOf(value);
            if (codec != null) {
                note(path, codec.kind());
                return codec.write(value);
            }
            if (value instanceof CharSequence || value instanceof Character || value instanceof UUID || value instanceof Enum<?>) {
                note(path, null);
                return value instanceof Enum<?> e ? e.name() : value.toString();
            }
            if (value instanceof Boolean) return value;
            WireType type = typeOf(value);
            if (type != null) {
                note(path, type);
                return special(type, value);
            }
            if (value instanceof Number) return value;
            if (!active.add(value)) throw new IllegalArgumentException("Wire.encode: " + (path.isEmpty() ? "the root" : path) + " refers back to itself");
            try {
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    map.forEach((k, v) -> out.put(String.valueOf(k), write(v, join(path, String.valueOf(k)))));
                    return out;
                }
                if (value instanceof Iterable<?> items) {
                    List<Object> out = new ArrayList<>();
                    for (Object item : items) out.add(write(item, path));
                    return out;
                }
                if (value.getClass().isArray()) {
                    List<Object> out = new ArrayList<>();
                    for (int i = 0; i < Array.getLength(value); i++) out.add(write(Array.get(value, i), path));
                    return out;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Properties.Property p : Properties.of(value.getClass())) out.put(p.name(), write(p.read(value), join(path, p.name())));
                return out;
            } finally {
                active.remove(value);
            }
        }

        /** 记下 path 上的值的种类；同一路径出现两种时报错。 */
        private void note(String path, WireType type) {
            if (found.containsKey(path) && found.get(path) != type) {
                throw new IllegalArgumentException("Wire.encode: values at " + (path.isEmpty() ? "the root" : path) + " are both "
                    + name(found.get(path)) + " and " + name(type) + "; one path carries one kind of value");
            }
            found.put(path, type);
        }

        /** 报错时种类的名字，文字是 text。 */
        private static String name(WireType type) { return type == null ? "text" : type.wireName(); }

        /** path 下的属性 key 的路径。 */
        static String join(String path, String key) {
            String step = key.replace("~", "~0").replace(".", "~1");
            return path.isEmpty() ? step : path + "." + step;
        }
    }
}
