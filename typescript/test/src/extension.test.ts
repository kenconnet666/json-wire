import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { fromWire, parseWire, registerWireCodec, toWire, wireText, wireTypeOf, type WireReader } from "@zerodep-dev/json-wire";
import { Decimal } from "decimal.js";

/** 接入方自己的金额类：按 decimal 写，读回成自己。 */
class Money {
  constructor(readonly amount: Decimal) {}
}

/** 子类的实例按父类注册的写法写。 */
class Price extends Money {}

/** 年月：按普通文字写，不进 $types。 */
class YearMonth {
  constructor(readonly year: number, readonly month: number) {}
  toString(): string {
    return `${this.year}-${String(this.month).padStart(2, "0")}`;
  }
}

registerWireCodec({ type: Money, kind: "decimal", write: (m) => m.amount.toFixed(), read: (t) => new Money(new Decimal(t)) });
registerWireCodec({
  type: YearMonth,
  write: String,
  read: (t) => {
    const m = /^(\d{4})-(\d{2})$/.exec(t);
    if (!m) throw new TypeError("expected YYYY-MM");
    return new YearMonth(Number(m[1]), Number(m[2]));
  },
});

describe("扩展：注册的值类型", () => {
  it("toWire、wireTypeOf、wireText 按注册的写法写，子类也算", () => {
    assert.deepEqual(toWire({ price: new Money(new Decimal("9.90")), list: [new Price(new Decimal("1"))] }), {
      price: "9.9",
      list: ["1"],
      $types: { price: "decimal", list: "decimal" },
    });
    assert.equal(wireTypeOf(new Money(new Decimal(1))), "decimal");
    assert.equal(wireText(new Money(new Decimal("0.0000001"))), "0.0000001");
  });

  it("种类不给时写成普通文字，与同一路径上的文字相容", () => {
    assert.deepEqual(toWire([{ month: new YearMonth(2024, 1) }, { month: "2024-02" }]), [{ month: "2024-01" }, { month: "2024-02" }]);
    assert.equal(wireTypeOf(new YearMonth(2024, 1)), undefined);
    assert.equal(wireText(new YearMonth(2024, 12)), "2024-12");
  });

  it("parseWire 按注册的类读；读不出时带上原文，没注册的类报错", () => {
    const money = parseWire(Money, "12.50");
    assert.ok(money instanceof Money && money.amount.equals("12.5"));
    assert.deepEqual(parseWire(YearMonth, "2024-03"), new YearMonth(2024, 3));
    assert.throws(() => parseWire(YearMonth, "2024/03"), /"2024\/03" is not a YearMonth/);
    assert.throws(() => parseWire(class Unknown {}, "x"), /Unknown is not registered/);
  });

  it("注册时校验 type 是类", () => {
    assert.throws(() => registerWireCodec({ type: undefined as never, write: String, read: String }), /must be a class/);
  });
});

describe("扩展：还原成什么可以换", () => {
  it("reader 只换关心的种类，其余交给 parseWire", () => {
    const reader: WireReader = (kind, text) => (kind === "decimal" ? Number(text) : parseWire(kind, text, { timeZone: "UTC" }));
    const value = fromWire<{ amount: number; id: bigint; sent: { hour: number } }>(
      { amount: "12.5", id: "9007199254740993", sent: "2024-01-31T13:45:00+08:00", $types: { amount: "decimal", id: "bigint", sent: "timestamptz" } },
      { reader },
    );
    assert.equal(value.amount, 12.5);
    assert.equal(value.id, 9007199254740993n);
    assert.equal(value.sent.hour, 5);
  });

  it("reader 可以把 decimal 读回注册的值类型，往返一致", () => {
    const wire = JSON.parse(JSON.stringify(toWire({ lines: [{ price: new Money(new Decimal("2.75")) }] })));
    const value = fromWire<{ lines: { price: Money }[] }>(wire, { reader: (kind, text) => (kind === "decimal" ? parseWire(Money, text) : parseWire(kind, text)) });
    assert.ok(value.lines[0]!.price instanceof Money);
    assert.ok(value.lines[0]!.price.amount.equals("2.75"));
  });

  it("reader 抛的错带上路径", () => {
    const reader: WireReader = () => {
      throw new RangeError("nope");
    };
    assert.throws(() => fromWire({ a: { b: "1" }, $types: { "a.b": "bigint" } }, { reader }), /fromWire: a\.b: nope/);
  });
});
