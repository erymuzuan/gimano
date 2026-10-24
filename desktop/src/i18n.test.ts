import { describe, expect, it } from "vitest";
import { dictionaries } from "./i18n";

describe("i18n", () => {
  it("has every key in both languages, with the same placeholders", () => {
    const ms = dictionaries.ms;
    const en = dictionaries.en;
    expect(Object.keys(en).sort()).toEqual(Object.keys(ms).sort());
    const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
    for (const k of Object.keys(ms) as (keyof typeof ms)[]) expect(vars(en[k]), k).toEqual(vars(ms[k]));
  });

  it("keeps the privacy promise visible in both languages", () => {
    expect(dictionaries.ms.nothingSent).toContain("Tiada apa dihantar ke pelayan Manotu");
    expect(dictionaries.ms.orgNote).toBe("Nota penganjur sahaja");
  });
});
