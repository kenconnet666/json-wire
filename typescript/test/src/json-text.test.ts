import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { CalendarDate } from "@internationalized/date";
import { parseWireJson, stringifyWire } from "@zerodep-dev/json-wire";
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
