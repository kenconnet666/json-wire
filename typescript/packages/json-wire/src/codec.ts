import type { WireType } from "./scalar.js";

/** 一个类（构造函数）；抽象类也行。 */
export type WireClass<T> = abstract new (...args: never[]) => T;

/**
 * 接入方自己的值类型在线上怎么写、怎么读（{@link registerWireCodec}），与 Java 的 `WireCodec` 对等：例如金额类按 `decimal` 写，
 * 年月类按普通文字写。注册后 `toWire`、`wireTypeOf`、`wireText`、`parseWire(类, 文字)` 都认它；
 * `fromWire` 不知道目标类型，还原成什么由它的 `reader` 选项决定。
 *
 *   registerWireCodec({ type: Money, kind: "decimal", write: (m) => m.amount.toFixed(), read: (t) => new Money(new Decimal(t)) });
 */
export interface WireCodec<T> {
  /** 值的类；子类的实例也按它写。 */
  readonly type: WireClass<T>;
  /** 线上的种类；不给时写成普通文字（不进 `$types`）。 */
  readonly kind?: WireType;
  /** 值 → 线上的文字。 */
  write(value: T): string;
  /** 线上的文字 → 值（`parseWire(类, 文字)` 用）；读不出时抛错。 */
  read(text: string): T;
}

/** 注册的值类型，后注册的在前。 */
const CODECS: WireCodec<unknown>[] = [];

/**
 * 注册接入方自己的值类型，也盖过内置的写法。注册表在模块里、整个运行环境共用，约定在应用启动时注册一次；
 * 同一个类注册多次时后注册的算数，值是好几个注册类的实例时也是后注册的优先。
 * 包被装了两份（两个版本）时各有各的注册表，注册要用应用实际导入的那一份。
 */
export function registerWireCodec<T>(codec: WireCodec<T>): void {
  if (typeof codec?.type !== "function") throw new TypeError("registerWireCodec: codec.type must be a class");
  CODECS.unshift(codec as WireCodec<unknown>);
}

/** value 是某个注册类的实例时是那个写法。 */
export function codecOf(value: unknown): WireCodec<unknown> | undefined {
  if (CODECS.length === 0 || typeof value !== "object" || value === null) return undefined;
  return CODECS.find((codec) => value instanceof codec.type);
}

/** 恰好为 type 注册的写法（读的时候要的是这个类本身，不是它的父类）。 */
export function codecFor<T>(type: WireClass<T>): WireCodec<T> | undefined {
  return CODECS.find((codec) => codec.type === type) as WireCodec<T> | undefined;
}
