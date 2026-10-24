// MapLibre wrapper: numbered stop pins, optional vehicle entrance, route lines by geometry
// source, unmatched stretches, freehand drawing. Pins are real <button>s (focusable, labelled),
// so every map object is also reachable from the keyboard.
import {
  AttributionControl, LngLatBounds, Map as MlMap, Marker, NavigationControl, ScaleControl, setWorkerUrl,
  type GeoJSONSource, type MapMouseEvent, type StyleSpecification,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";

import { h } from "./dom";
import { kindName, t } from "./i18n";
import type { DocView, LatLng, Selection } from "./types";

setWorkerUrl(workerUrl);

export type MapMode = "select" | "point" | "draw";

export interface MapHandlers {
  selectStop(id: string): void;
  selectPoint(routeId: string, index: number): void;
  moveStop(id: string, p: LatLng): void;
  moveEntrance(id: string, p: LatLng): void;
  movePoint(routeId: string, index: number, p: LatLng): void;
  /** Click on empty map (point mode appends, select mode adds nothing). */
  mapClick(p: LatLng): void;
  /** Click on a route line: insert a point on that leg. */
  lineClick(routeId: string, legIndex: number, p: LatLng): void;
  drawEnd(points: LatLng[]): void;
}

const COBALT = "#0047AB";
const CORAL = "#FF6B4A";
const MUTED = "#4E5D78";

function offlineStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [{ id: "bg", type: "background", paint: { "background-color": "#EEF2F8" } }],
  };
}

type Feature = GeoJSON.Feature<GeoJSON.LineString, Record<string, string | number | boolean>>;

export class MapView {
  readonly map: MlMap;
  private markers: Marker[] = [];
  private ready = false;
  private last?: { view: DocView; activeRoute?: string; sel?: Selection | null };
  private mode: MapMode = "select";
  private drawing: LatLng[] | null = null;
  private fitted = false;

  constructor(container: HTMLElement, online: boolean, styleUrl: string, private handlers: MapHandlers) {
    this.map = new MlMap({
      container,
      style: online ? styleUrl : offlineStyle(),
      center: [101.6869, 3.139],
      zoom: 11,
      attributionControl: false,
      keyboard: true,
      dragRotate: false,
    });
    this.map.addControl(
      new AttributionControl({
        compact: false,
        // Online, the OpenFreeMap style carries its own OSM/OpenMapTiles attribution. Offline,
        // route geometry still comes from OSM, so the credit stays visible.
        customAttribution: online ? undefined : "© OpenStreetMap contributors",
      }),
    );
    this.map.addControl(new NavigationControl({ showCompass: false }), "top-right");
    this.map.addControl(new ScaleControl({ unit: "metric" }), "bottom-left");
    this.map.on("load", () => {
      this.addLayers();
      this.ready = true;
      if (this.last) this.render(this.last.view, this.last.activeRoute, this.last.sel);
    });
    this.map.on("click", (e) => this.onClick(e));
    this.map.on("mousedown", (e) => this.onDown(e));
    this.map.on("mousemove", (e) => this.onMove(e));
    this.map.on("mouseup", () => this.onUp());
    for (const layer of ["route-hit"]) {
      this.map.on("mouseenter", layer, () => {
        if (this.mode === "point") this.map.getCanvas().style.cursor = "copy";
      });
      this.map.on("mouseleave", layer, () => this.applyCursor());
    }
  }

  setMode(mode: MapMode): void {
    this.mode = mode;
    if (mode === "draw") this.map.dragPan.disable();
    else this.map.dragPan.enable();
    this.applyCursor();
  }

  private applyCursor(): void {
    this.map.getCanvas().style.cursor = this.mode === "select" ? "" : "crosshair";
  }

  center(): LatLng {
    const c = this.map.getCenter();
    return { lat: Math.round(c.lat * 1e6) / 1e6, lng: Math.round(c.lng * 1e6) / 1e6 };
  }

  resize(): void {
    this.map.resize();
  }

  destroy(): void {
    this.map.remove();
  }

  private addLayers(): void {
    const empty: GeoJSON.FeatureCollection = { type: "FeatureCollection", features: [] };
    for (const id of ["routes", "unmatched", "draft"]) this.map.addSource(id, { type: "geojson", data: empty });
    this.map.addLayer({
      id: "route-casing", type: "line", source: "routes",
      filter: ["all", ["==", ["get", "active"], true], ["in", ["get", "state"], ["literal", ["routed", "matched"]]]],
      layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": "#FFFFFF", "line-width": 9 },
    });
    this.map.addLayer({
      id: "route-road", type: "line", source: "routes",
      filter: ["in", ["get", "state"], ["literal", ["routed", "matched"]]],
      layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": ["case", ["get", "active"], COBALT, "#A9C2EE"], "line-width": ["case", ["get", "active"], 5, 4] },
    });
    this.map.addLayer({
      id: "route-sketch", type: "line", source: "routes",
      filter: ["in", ["get", "state"], ["literal", ["sketch", "gpx"]]],
      layout: { "line-join": "round" },
      paint: { "line-color": ["case", ["get", "active"], MUTED, "#9AA6BC"], "line-width": 3, "line-dasharray": [2, 1.5] },
    });
    this.map.addLayer({
      id: "route-unmatched", type: "line", source: "unmatched",
      paint: { "line-color": CORAL, "line-width": 4, "line-dasharray": [1.5, 1.2] },
    });
    this.map.addLayer({
      id: "route-hit", type: "line", source: "routes",
      filter: ["==", ["get", "active"], true],
      paint: { "line-color": "#000000", "line-opacity": 0, "line-width": 16 },
    });
    this.map.addLayer({
      id: "draft", type: "line", source: "draft",
      layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": CORAL, "line-width": 4 },
    });
  }

  render(view: DocView, activeRoute?: string, sel?: Selection | null): void {
    this.last = { view, activeRoute, sel };
    if (!this.ready) return;
    const focused = (document.activeElement as HTMLElement | null)?.dataset?.mapKey;
    const doc = view.doc;
    const lines: Feature[] = [];
    const unmatched: Feature[] = [];
    for (const r of doc.routes) {
      r.legs.forEach((l, i) => {
        // Unresolved legs are never drawn: no straight line pretending to be a road.
        if (l.geometry.length < 2 || l.state === "pending" || l.state === "unresolved") return;
        lines.push({
          type: "Feature",
          properties: { state: l.state, routeId: r.id, legIndex: i, active: r.id === activeRoute },
          geometry: { type: "LineString", coordinates: l.geometry.map((p) => [p.lng, p.lat]) },
        });
        if (l.drawn && l.unmatched?.length) {
          for (const [a, b] of l.unmatched) {
            const seg = l.drawn.slice(Math.max(0, a - 1), Math.min(l.drawn.length, b + 2));
            if (seg.length >= 2) {
              unmatched.push({
                type: "Feature", properties: { routeId: r.id, legIndex: i },
                geometry: { type: "LineString", coordinates: seg.map((p) => [p.lng, p.lat]) },
              });
            }
          }
        }
      });
    }
    (this.map.getSource("routes") as GeoJSONSource).setData({ type: "FeatureCollection", features: lines });
    (this.map.getSource("unmatched") as GeoJSONSource).setData({ type: "FeatureCollection", features: unmatched });

    for (const m of this.markers) m.remove();
    this.markers = [];
    const editable = !view.readonly;
    const selStop = sel?.type === "stop" ? sel.id : undefined;
    const selPoint = sel?.type === "point" ? sel : undefined;

    doc.stops.forEach((s, i) => {
      const key = `stop:${s.id}`;
      const el = h(
        "button",
        {
          class: `pin kind-${s.kind}${selStop === s.id ? " selected" : ""}`,
          type: "button",
          "aria-label": `${t("stop")} ${i + 1}: ${s.label} (${kindName(s.kind)})`,
          "aria-pressed": selStop === s.id ? "true" : "false",
          "data-map-key": key,
        },
        h("span", {}, String(i + 1)),
      );
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        this.handlers.selectStop(s.id);
      });
      const m = new Marker({ element: el, anchor: "bottom", draggable: editable }).setLngLat([s.lng, s.lat]).addTo(this.map);
      m.on("dragend", () => {
        const p = m.getLngLat();
        this.handlers.moveStop(s.id, { lat: p.lat, lng: p.lng });
      });
      this.markers.push(m);
      if (s.entrance && (selStop === s.id || !activeRoute)) {
        const ek = `entrance:${s.id}`;
        const ee = h("button", {
          class: "pin-entrance", type: "button", "data-map-key": ek,
          "aria-label": `${t("entrance")}: ${s.label}`,
        }, "E");
        ee.addEventListener("click", (e) => {
          e.stopPropagation();
          this.handlers.selectStop(s.id);
        });
        const em = new Marker({ element: ee, draggable: editable }).setLngLat([s.entrance.lng, s.entrance.lat]).addTo(this.map);
        em.on("dragend", () => {
          const p = em.getLngLat();
          this.handlers.moveEntrance(s.id, { lat: p.lat, lng: p.lng });
        });
        this.markers.push(em);
      }
    });

    const route = doc.routes.find((r) => r.id === activeRoute);
    route?.points.forEach((p, i) => {
      if (p.stopId) return; // the stop pin is the handle
      const key = `point:${route.id}:${i}`;
      const isSel = selPoint?.routeId === route.id && selPoint.index === i;
      const el = h("button", {
        class: `shape-point${isSel ? " selected" : ""}`, type: "button", "data-map-key": key,
        "aria-label": `${t("point", { n: i + 1 })} · ${t("shaping")}`, "aria-pressed": isSel ? "true" : "false",
      });
      el.addEventListener("click", (e) => {
        e.stopPropagation();
        this.handlers.selectPoint(route.id, i);
      });
      const m = new Marker({ element: el, draggable: editable }).setLngLat([p.lng, p.lat]).addTo(this.map);
      m.on("dragend", () => {
        const q = m.getLngLat();
        this.handlers.movePoint(route.id, i, { lat: q.lat, lng: q.lng });
      });
      this.markers.push(m);
    });

    if (focused) {
      (this.map.getContainer().querySelector(`[data-map-key="${CSS.escape(focused)}"]`) as HTMLElement | null)?.focus();
    }
    if (!this.fitted) {
      this.fitted = true;
      this.fit(view);
    }
  }

  fit(view: DocView): void {
    const b = new LngLatBounds();
    let n = 0;
    for (const s of view.doc.stops) { b.extend([s.lng, s.lat]); n++; }
    for (const r of view.doc.routes) for (const p of r.points) { b.extend([p.lng, p.lat]); n++; }
    if (n === 1) this.map.jumpTo({ center: b.getCenter(), zoom: 15 });
    else if (n > 1) this.map.fitBounds(b, { padding: 80, maxZoom: 16, duration: 0 });
  }

  flyTo(p: LatLng): void {
    this.map.easeTo({ center: [p.lng, p.lat] });
  }

  private onClick(e: MapMouseEvent): void {
    if (this.mode !== "point" || !this.ready) return;
    const p = { lat: e.lngLat.lat, lng: e.lngLat.lng };
    const hits = this.map.queryRenderedFeatures(e.point, { layers: ["route-hit"] });
    const hit = hits[0]?.properties as { routeId?: string; legIndex?: number } | undefined;
    if (hit?.routeId !== undefined && hit.legIndex !== undefined) this.handlers.lineClick(hit.routeId, Number(hit.legIndex), p);
    else this.handlers.mapClick(p);
  }

  private onDown(e: MapMouseEvent): void {
    if (this.mode !== "draw" || (e.originalEvent.target as HTMLElement).closest(".maplibregl-marker")) return;
    this.drawing = [{ lat: e.lngLat.lat, lng: e.lngLat.lng }];
  }

  private onMove(e: MapMouseEvent): void {
    if (!this.drawing) return;
    const last = this.drawing[this.drawing.length - 1];
    const a = this.map.project([last.lng, last.lat]);
    if (Math.hypot(a.x - e.point.x, a.y - e.point.y) < 4) return;
    this.drawing.push({ lat: e.lngLat.lat, lng: e.lngLat.lng });
    (this.map.getSource("draft") as GeoJSONSource).setData({
      type: "Feature", properties: {},
      geometry: { type: "LineString", coordinates: this.drawing.map((p) => [p.lng, p.lat]) },
    });
  }

  private onUp(): void {
    const pts = this.drawing;
    this.drawing = null;
    (this.map.getSource("draft") as GeoJSONSource | undefined)?.setData({ type: "FeatureCollection", features: [] });
    if (pts && pts.length >= 2) {
      const r = pts.map((p) => ({ lat: Math.round(p.lat * 1e6) / 1e6, lng: Math.round(p.lng * 1e6) / 1e6 }));
      this.handlers.drawEnd(r.slice(0, 5000));
    }
  }
}
