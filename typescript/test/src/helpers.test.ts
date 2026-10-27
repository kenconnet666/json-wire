import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { CalendarDate } from "@internationalized/date";
import { jsonAt, parseWire, parseWireJson, stringifyWire, wireText } from "@zerodep-dev/json-wire";
import { Decimal } from "decimal.js";

const SAMPLE = fileURLToPath(new URL("../../../spec/order.json", import.meta.url));

describe("typed JSON 文字的往返", () => {
  it("stringifyWire 与 parseWireJson 互逆，null 读成 null", () => {
    const text = stringifyWire({ id: 9007199254740993n, amount: new Decimal("12.50"), on: new CalendarDate(2024, 1, 31) });
    assert.equal(text, '{"id":"9007199254740993","amount":"12.5","on":"2024-01-31","$types":{"id":"bigint","amount":"decimal","on":"date"}}');
    const value = parseWireJson<{ id: bigint; amount: Decimal; on: CalendarDate }>(text)!;
    assert.equal(value.id, 9007199254740993n);
    assert.ok(value.amount.equals("12.5"));
    assert.equal(value.on.compare(new CalendarDate(2024, 1, 31)), 0);
    assert.equal(parseWireJson(null), null);
    assert.equal(parseWireJson(undefined), null);
    assert.deepEqual(parseWireJson(stringifyWire([1n, 2n])), [1n, 2n]);
  });

  it("parseWireJson 读共用样例，选项照传给 fromWire", () => {
    const order = parseWireJson<Record<string, unknown>>(readFileSync(SAMPLE, "utf8"), { reader: (kind, text) => `${kind}:${text}` })!;
    assert.equal(order.id, "bigint:9007199254740993");
    assert.equal(order.code, "PO-1");
  });
});

describe("jsonAt：按点路径取值", () => {
  const tree = JSON.parse('{"buyer":{"name":"甲"},"rows":[{"price":1.5},{"price":2}],"n":null}');

  it("沿对象往下，数组按下标，空路径是树本身", () => {
    assert.equal(jsonAt(tree, "buyer.name"), "甲");
    assert.equal(jsonAt(tree, "rows.0.price"), 1.5);
    assert.equal(jsonAt(tree, "rows.1.price"), 2);
    assert.equal(jsonAt(tree, ""), tree);
    assert.equal(jsonAt(tree, "n"), null);
  });

  it("取不到时是 undefined，不碰原型上的属性", () => {
    for (const missing of ["buyer.age", "buyer.name.first", "rows.2.price", "rows.x", "rows.-1", "rows.length", "n.x", "buyer..name", "buyer.toString"]) {
      assert.equal(jsonAt(tree, missing), undefined, missing);
    }
    assert.equal(jsonAt(null, "a"), undefined);
  });
});

describe("类型转换：TypeScript 用 parseWire(kind, wireText(value))", () => {
  it("与 Java 的 Wire.convert 同样严格", () => {
    assert.equal(parseWire("integer", wireText(12)), 12);
    assert.equal(parseWire("bigint", wireText(new Decimal("3.00"))), 3n);
    assert.ok((parseWire("decimal", wireText(0.1)) as Decimal).equals("0.1"));
    assert.throws(() => parseWire("bigint", wireText(12.5)), /"12.5" is not an integer/);
    assert.throws(() => parseWire("integer", wireText(new Decimal("12.5"))), /"12.5" is not a safe integer/);
  });
});
