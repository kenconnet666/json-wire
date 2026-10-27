# json-wire

线上的 typed JSON：Long、BigDecimal、bigint、Decimal、日期、时间、字节等 JSON 不能原样承载（或 JavaScript 读了会丢精度）的值写成文字，根对象上的 `$types` 记下它们的位置和种类，对方据此还原。Java 与 TypeScript 两边是同一套写法，对照同一份样例 [`spec/order.json`](spec/order.json)。

```json
{ "id": "9007199254740993", "amount": "12.5", "on": "2024-01-31", "lines": [{ "qty": "2.75" }],
  "$types": { "id": "bigint", "amount": "decimal", "on": "date", "lines.qty": "decimal" } }
```

| | 坐标 | 依赖 |
| --- | --- | --- |
| Java 17+ | `org.dpdns.zerodep:json-wire`，包 `org.dpdns.zerodep.wire` | 无 |
| TypeScript | `@zerodep-dev/json-wire`（ESM） | peer：`decimal.js`、`@internationalized/date` |

## Java

```xml
<dependency>
    <groupId>org.dpdns.zerodep</groupId>
    <artifactId>json-wire</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@GetMapping("/orders/{id}")
Object order(@PathVariable long id) { return Wire.encode(service.find(id)); }   // Map、List 组成的树，Jackson、Gson 都能原样写出

Object order = Wire.decode(Json.read(text));        // 按 $types 还原成 Long、BigDecimal、LocalDate……
LocalDate on = Wire.parse(LocalDate.class, "2024-01-31");
String q = Wire.text(new BigDecimal("1E-7"));       // "0.0000001"
```

`Json.read` / `Json.write` 是零依赖的 JSON 读写（数字不经 double），给不经 Web 框架的 JSON 用。

## TypeScript

```ts
import { fromWire, toWire, wireText, parseWire } from "@zerodep-dev/json-wire";

const body = JSON.stringify(toWire({ id: 9007199254740993n, amount: new Decimal("12.50"), on: new CalendarDate(2024, 1, 31) }));
const order = fromWire<Order>(JSON.parse(body), { timeZone: "Asia/Shanghai" });
wireText(new Decimal("1e-7"));        // "0.0000001"
parseWire("date", "2024-01-31");      // CalendarDate
```

## 文档

- [docs/format.md](docs/format.md)：线上格式、种类与两边类型的对应、两边的 API、设计取舍。
- [docs/extending.md](docs/extending.md)：注册自己的值类型、换还原方式、Java 对象取哪些属性。
- [docs/release.md](docs/release.md)：手动发布到 Maven 中央仓库与 npm。

## 开发

```powershell
.\mvnw.cmd verify                                  # Java：编译、测试，生成 jar、源码包与 javadoc 包
.\mvnw.cmd install                                 # 装进本机 ~/.m2，别的项目按上面的坐标引用
cd typescript; pnpm install; pnpm test             # TypeScript：编译并运行 node:test
```

本机别的项目引用 TypeScript 包：先在 `typescript` 下 `pnpm build` 生成 `packages/json-wire/dist`，再在那边写 `"@zerodep-dev/json-wire": "link:../json-wire/typescript/packages/json-wire"`。

| 目录 | 内容 |
| --- | --- |
| `java/json-wire` | Java 模块 |
| `typescript/packages/json-wire` | npm 包 |
| `typescript/test` | TypeScript 的测试（引用工作区里的包） |
| `spec` | 两边共用的样例 |
| `docs` | 文档 |

许可证：Apache-2.0。
