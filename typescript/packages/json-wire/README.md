# @zerodep-dev/json-wire

线上的 typed JSON：bigint、Decimal、日历日期、时间、字节等 JSON 不能原样承载的值写成文字，根对象上的 `$types` 记下它们的位置，对方据此还原。Java 一侧是 `org.dpdns.zerodep:json-wire`，两边是同一套写法。

```sh
pnpm add @zerodep-dev/json-wire decimal.js @internationalized/date
```

```ts
import { fromWire, toWire } from "@zerodep-dev/json-wire";

const body = JSON.stringify(toWire({ id: 9007199254740993n, amount: new Decimal("12.50"), on: new CalendarDate(2024, 1, 31) }));
// {"id":"9007199254740993","amount":"12.5","on":"2024-01-31","$types":{"id":"bigint","amount":"decimal","on":"date"}}
const order = fromWire<Order>(JSON.parse(body));   // id 又是 bigint，amount 又是 Decimal，on 又是 CalendarDate
```

`stringifyWire` / `parseWireJson` 是上面两步的简写。

格式、两边的 API 与扩展方式见仓库：<https://github.com/kenconnet666/json-wire>。
