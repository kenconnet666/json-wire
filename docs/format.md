# 线上格式

typed JSON 要解决的问题：Long、BigInteger、BigDecimal 写成 JSON 数字，到 JavaScript 里会丢精度（`9007199254740993` 读成 `…992`，金额按 double 运算）；TypeScript 的 `bigint` 写不进 `JSON.stringify`，decimal.js 的 `toJSON` 会用科学计数法，`CalendarDate` 写出来是一个对象。

做法：JSON 能原样承载的值照常写，其余的值写成文字，根对象上的 `$types` 记下它们的位置和种类，对方据此还原。格式与 SQL、Web 框架都无关，任何接口的请求体、响应、消息都可以用。

## 种类

JSON 能原样承载的值照常：文字、布尔、null、安全范围内的整数（Java 的 Integer、Short、Byte）、有限的浮点数。其余的值：

| 种类 | Java | TypeScript | 文字 |
| --- | --- | --- | --- |
| `bigint` | Long、BigInteger | `bigint` | `"9007199254740993"` |
| `decimal` | BigDecimal | decimal.js 的 `Decimal` | `"12.50"`，不用科学计数法 |
| `double` | NaN、±Infinity 的 Double、Float | NaN、±Infinity 的 `number` | `"NaN"`、`"Infinity"`、`"-Infinity"` |
| `date` | LocalDate（java.sql.Date） | `CalendarDate` | `"2024-01-31"` |
| `time` | LocalTime（java.sql.Time） | `Time` | `"13:45:00"` |
| `timestamp` | LocalDateTime（java.sql.Timestamp） | `CalendarDateTime` | `"2024-01-31T13:45:00.5"`：总有秒，小数去掉末尾的零 |
| `timestamptz` | OffsetDateTime、ZonedDateTime、Instant、java.util.Date | `ZonedDateTime`、`Date` | `"2024-01-31T05:45:00Z"`：换算成 UTC |
| `bytes` | byte[] | `Uint8Array` | 标准 base64（带 `=` 补齐） |

TypeScript 的日期类来自 `@internationalized/date`。还原时 Java 默认得到表里的第一个类型（bigint 放不进 long 时是 BigInteger，timestamptz 是 Instant），TypeScript 得到表里的类型（timestamptz 是指定时区的 `ZonedDateTime`）；两边都可以换（见 [extending.md](extending.md)）。

```json
{ "id": "9007199254740993", "amount": "12.5", "lines": [{ "qty": "2.75" }],
  "$types": { "id": "bigint", "amount": "decimal", "lines.qty": "decimal" } }
```

完整的样例是 [`spec/order.json`](../spec/order.json)，Java 与 TypeScript 的测试都拿它对照。

## 规则

- **路径**：从根开始，用点连接属性名；数组不占一段（`lines.qty` 是每一行的 qty）；属性名里的 `~`、`.` 写成 `~0`、`~1`（同 JSON Pointer），所以 `byMonth` 的键 `2024.01` 是 `byMonth.2024~101`。
- **一个路径只有一种值**：同一路径上既有 Decimal 又有文字时编码报错，否则对方无从还原。null 不占种类。
- **根不是对象**（列表、单个值）又有要还原的值时，包成 `{ "$value": 根, "$types": { "$value…": … } }`，还原时拆开。
- `$types`、`$value` 在根对象上是保留的键，编码时根对象带着它们会报错。
- **没标类型的数**：Java 的 `Wire.decode` 按 JavaScript 的数还原（放得进 int 的整数是 Integer，其余是 Double），这样两边对同一份 JSON 得到的值一致；JSON 库读出的 Long、BigDecimal 原样再编码会变成 bigint、decimal。
- **对象取哪些属性**：Java 取 record 的组件；其余的类取公开的 getter（`getX`、布尔的 `isX`）和公开字段，属性名按字段的写法（同 Jackson 默认看到的）。TypeScript 取自身可枚举的属性（undefined 与函数跳过），有 `toJSON` 的先调用它，Map 写成对象、Set 写成数组。枚举写成名字，Optional 取里面的值。循环引用报错。

## 两边的 API

| | Java `org.dpdns.zerodep.wire` | TypeScript `@zerodep-dev/json-wire` |
| --- | --- | --- |
| 编码 | `Wire.encode(value)` → Map、List、String、Number、Boolean 组成的树，任何 JSON 库都能原样写出 | `toWire(value)` → JSON 值 |
| 还原 | `Wire.decode(tree)`、`Wire.decode(tree, reader)`：不改传入的树，返回不可修改的新树 | `fromWire(json, { timeZone, reader })`：就地改写，通常它刚由 `JSON.parse` 得到 |
| typed JSON 文字 | `Wire.toJson(value)`、`Wire.fromJson(text)`（null 读成 null） | `stringifyWire(value)`、`parseWireJson(text, options)`（null、undefined 读成 null） |
| 单个值的文字 | `Wire.text(value)`、`Wire.parse(Class, text)`、`Wire.read(kind, text)` | `wireText(value)`、`parseWire(kind 或类, text)` |
| 值的种类 | `Wire.typeOf(value)` | `wireTypeOf(value)` |
| 接入方的值类型 | `Wire.register(new WireCodec<>(…))` | `registerWireCodec({ … })` |
| JSON 文字 | `Json.read(text)`、`Json.write(tree)`：零依赖的读写，数字不经 double | 用 `JSON.parse`、`JSON.stringify` |

Java 一侧绑定到有类型的对象时不需要 `decode`：Spring 的 Jackson 本来就把 `"12.50"`、ISO 日期读成 BigDecimal、LocalDate，Spring Boot 默认忽略不认识的 `$types`。没有类型可依的树（表单的值、`Map<String, Object>`）才用 `Wire.decode`。

## 地址栏里的值

地址栏参数没有 `$types`，按接收方期望的类型读：Spring 按 DTO 字段的类型绑定（Long、BigDecimal、ISO 日期都能读）；按列类型读的值用 `Wire.parse` / `parseWire`。写的一方用 `Wire.text` / `wireText`：Decimal 不用科学计数法，日期是 ISO，空值是空字符串。读的格式比写宽一点：日期时间也接受空格分隔、可以省掉秒，timestamptz 接受任意时差。

## 为什么这样设计

- **参照**：protobuf 的 JSON 映射把 int64 写成文字、bytes 写成 base64、Timestamp 写成 RFC 3339；Google 的 `google.type.Decimal` 用文字。`$types` 是我们加的：protobuf 有 schema，HTTP 接口没有，而 TypeScript 一侧要在没有 schema 的情况下还原。
- **Java 不依赖 Jackson**：编码的结果只有 JSON 的基本结构，Jackson 2、Jackson 3、Gson 都原样写出，也不受应用的 ObjectMapper 配置（数字写法、命名策略）影响。依赖 Jackson 能少写一次 `Wire.encode`，但要同时适配 Jackson 2 与 Jackson 3 两套 API，还要在根上补 `$types`，收益不抵这份维护。代价是不认 `@JsonIgnore`、`@JsonProperty`：要隐藏或改名字段时另做一个返回用的 record（见 [extending.md](extending.md)）。
- **TypeScript 的日期与小数用现成的库**：`@internationalized/date` 与 decimal.js 是 peer 依赖，由应用决定版本，避免装两份。
