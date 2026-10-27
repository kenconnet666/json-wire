# 扩展

两边对等的扩展口有两个：注册接入方自己的值类型（怎么写、怎么按类型读），换还原方式（`$types` 标出的文字还原成什么）。对象取哪些属性目前不开放，原因与将来的做法见最后一节。

## 接入方自己的值类型

值类型（金额、年月、编号这类）注册一个写法后，编码、单个值的文字、按类型读都认它，也盖过内置的写法。`kind` 是线上的种类：给了就进 `$types`，对方能还原；不给时写成普通文字。

Java：

```java
Wire.register(new WireCodec<>(Money.class, WireType.DECIMAL, m -> m.amount().toPlainString(), t -> new Money(new BigDecimal(t))));
Wire.encode(Map.of("price", money));   // {price: "9.90", $types: {price: "decimal"}}
Wire.parse(Money.class, "9.90");       // Money
```

TypeScript：

```ts
registerWireCodec({ type: Money, kind: "decimal", write: (m) => m.amount.toFixed(), read: (t) => new Money(new Decimal(t)) });
toWire({ price: money });               // { price: "9.9", $types: { price: "decimal" } }
parseWire(Money, "9.90");               // Money
```

- 子类（Java 里也包括接口的实现）的实例按父类型注册的写法写；按类型读（`Wire.parse`、`parseWire(类, 文字)`）要的是注册的类型本身。
- 同一个类型注册多次时后注册的算数；值是好几个注册类型的实例时也是后注册的优先。
- 读不出时 `read` 抛错（Java 抛 IllegalArgumentException），报错消息会带上原文。

### 注册表是全局的

注册表是静态的：Java 是整个 JVM（准确说是加载 `Wire` 的类加载器）共用的一张表，TypeScript 是模块里的一张表。约定在应用启动时、开始处理请求之前注册一次：

- Java 的注册线程安全（写时复制的列表），之后的编码、读都看得到；但与注册并发的编码看不看得到这次注册没有保证。
- 库自己不要注册：两个库给同一个类型注册不同写法时，谁后加载谁算数。值类型属于哪个应用，就由那个应用注册。
- TypeScript 的包被装了两份（两个版本）时各有各的注册表，要用应用实际导入的那一份注册。
- 不提供注销：测试里需要不同写法时用不同的类型。

## 还原方式

`$types` 只说种类，不说目标类型，所以还原成什么由调用方决定。默认的还原见 [format.md](format.md)；要别的类型时包一层，只换自己关心的种类，其余交给默认的：

Java（`WireReader`，默认是 `Wire.read`）：

```java
WireReader reader = (kind, text) -> kind == WireType.TIMESTAMPTZ ? OffsetDateTime.parse(text) : Wire.read(kind, text);
Wire.decode(tree, reader);
```

TypeScript（`WireReader`，默认是 `parseWire`）：

```ts
const reader: WireReader = (kind, text) => (kind === "decimal" ? Number(text) : parseWire(kind, text, { timeZone: "UTC" }));
fromWire(json, { reader });
```

- 读回注册的值类型：`kind == DECIMAL ? Wire.parse(Money.class, text) : Wire.read(kind, text)`，TypeScript 是 `parseWire(Money, text)`。
- 给了 `reader` 时 TypeScript 的 `timeZone` 选项不再起作用，要的话在 reader 里自己传给 `parseWire`。
- reader 抛的错会在消息前加上路径（`Wire.decode: lines.qty: …`、`fromWire: lines.qty: …`）。

## 对象取哪些属性（Java）

Java 一侧取 record 的组件、普通类的公开 getter 与公开字段（`Properties`，按类缓存）。目前不开放挑选属性的扩展口，要隐藏或改名字段时：

- **推荐**：给接口单独做一个返回用的 record，`Wire.encode(new OrderView(…))`。接口的形状一目了然，也不受实体类以后加 getter 的影响。
- 临时的场合用 Map：`Wire.encode(Map.of("id", order.getId(), …))`。

之所以没加：没有调用方需要，而按类挑选属性的规则（白名单、黑名单、改名、按视图）一旦开放就是长期的契约，容易做成半个 Jackson。真有需要时，打算这样加，和 TypeScript 的 `toJSON` 对等：

```java
// 草案，尚未实现：编码遇到 Order 时先换成 view 的结果再往下写，路径不变
Wire.view(Order.class, o -> new OrderView(o.getId(), o.getAmount()));
```

实现位置是 `Wire.Encoder.write`：在 `Properties.of` 之前查这张表，与 `WireCodec` 的注册表共用同样的约定（启动时注册、后注册的优先）。`Properties` 本身保持包内可见，不暴露反射细节。
