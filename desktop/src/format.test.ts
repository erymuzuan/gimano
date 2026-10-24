import { describe, expect, it } from "vitest";
import { daysBetween, distance, duration, fromLocalInput, offsetOf, parseCoords, parsePath, toLocalInput } from "./format";

describe("format", () => {
  it("formats distance and duration", () => {
    expect(distance(850)).toBe("850 m");
    expect(distance(58200)).toBe("58.2 km");
    expect(distance(158200)).toBe("158 km");
    expect(duration(13200)).toBe("3 j 40 min");
    expect(duration(300)).toBe("5 min");
  });

  it("round-trips datetime-local keeping the event offset", () => {
    const s = "2026-11-15T11:00:00+08:00";
    expect(toLocalInput(s)).toBe("2026-11-15T11:00");
    expect(offsetOf(s)).toBe("+08:00");
    expect(offsetOf("2026-11-16T08:00:00Z")).toBe("+00:00");
    expect(fromLocalInput(toLocalInput(s), offsetOf(s))).toBe(s);
    expect(fromLocalInput("", "+08:00")).toBe("");
  });

  it("parses typed coordinates (keyboard alternative to dragging)", () => {
    expect(parseCoords("2.9871, 101.7930")).toEqual({ lat: 2.9871, lng: 101.793 });
    expect(parseCoords("2.98710012345 101.79")).toEqual({ lat: 2.9871, lng: 101.79 });
    expect(parseCoords("95, 101")).toBeNull();
    expect(parseCoords("abc")).toBeNull();
  });

  it("lists event days", () => {
    expect(daysBetween("2026-11-15T08:00:00+08:00", "2026-11-16T18:00:00+08:00")).toEqual(["2026-11-15", "2026-11-16"]);
    expect(daysBetween("", "")).toEqual([]);
  });

  it("maps validation paths", () => {
    expect(parsePath("stops[3].label")).toEqual({ list: "stops", index: 3, field: "label" });
    expect(parsePath("routes[0]")).toEqual({ list: "routes", index: 0, field: undefined });
    expect(parsePath("title")).toEqual({ field: "title" });
  });
});
