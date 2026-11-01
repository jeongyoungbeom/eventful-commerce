"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const api = location.pathname.startsWith("/api/") ? "/api/inventory" : "/inventory";
  let token = "", outbound = null, transferId = "", countId = "";
  const text = (tag, value, className) => { const e = document.createElement(tag); e.textContent = value ?? "—"; if (className) e.className = className; return e; };
  const field = (form, name) => $(form).elements.namedItem(name);
  const value = (form, name) => field(form, name).value.trim();
  const set = (form, name, v) => { field(form, name).value = v; };
  const labels = { OPEN: "기준 기록", MEASURED: "측정 완료", APPLIED: "조정 완료", CANCELED: "취소", RESERVED: "예약", ALLOCATED: "할당", PENDING: "처리 중", READY: "피킹 대기", PICKING: "피킹 중", PACKED: "포장 완료", SHIPPED: "출고 완료", HELD: "이동 확보", IN_TRANSIT: "이동 중", PARTIALLY_RECEIVED: "부분 도착", RECEIVED: "도착 완료", GOOD: "정상", DAMAGED: "불량", INSPECTION: "검수 중" };
  const state = s => labels[s] || s;
  function notify(message, error = false) { $("notice").textContent = message; $("notice").className = error ? "error" : ""; }
  function disconnect() {
    token = ""; outbound = null; transferId = ""; countId = "";
    $("workspace").hidden = true; $("logout").hidden = true; $("login-panel").hidden = false;
    field("login-form", "token").value = "";
    for (const id of ["stock-summary", "stock-table", "inbound-result", "inbound-table", "outbound-detail", "transfer-detail", "count-detail", "reconciliation-result", "reservation-table", "outbound-table", "transfer-table", "count-table", "skus", "warehouses", "suppliers"]) $(id).replaceChildren();
  }
  // 수량이 JavaScript의 안전한 정수 범위를 넘으면 문자열로 보존한다.
  const parse = raw => JSON.parse(raw.replace(/"(?:\\.|[^"\\])*"|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g, (part, number) => number && !Number.isSafeInteger(Number(number)) ? JSON.stringify(number) : part));
  async function request(path, method = "GET", body) {
    const response = await fetch(api + path, { method, headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000), cache: "no-store" });
    const raw = await response.text();
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) disconnect();
      let message;
      try { message = parse(raw).message; } catch { /* 오류 HTML은 화면에 렌더링하지 않는다. */ }
      throw new Error(message || ({ 401: "토큰이 만료됐거나 유효하지 않습니다. 다시 연결하세요.", 403: "관리자 권한이 필요합니다.", 409: "기준이나 작업 상태가 바뀌었습니다. 기록을 다시 조회하세요." }[response.status] || "요청을 처리할 수 없습니다 (" + response.status + ")"));
    }
    return raw ? parse(raw) : null;
  }
  function bind(id, action) {
    $(id).addEventListener("submit", async event => {
      event.preventDefault();
      const buttons = [...$(id).querySelectorAll("button")]; buttons.forEach(b => b.disabled = true);
      try { await action(); notify("처리했습니다. 작업 기록을 확인하세요."); } catch (e) { notify(e.message, true); }
      finally { buttons.forEach(b => b.disabled = false); }
    });
  }
  function click(id, action) { $(id).addEventListener("click", async () => { try { await action(); } catch (e) { notify(e.message, true); } }); }
  function number(form, name, min = 0) {
    const n = Number(value(form, name));
    if (!value(form, name) || !Number.isSafeInteger(n) || n < min) throw new Error("수량·금액은 " + min + " 이상의 안전한 정수로 입력하세요.");
    return n;
  }
  const query = obj => "?" + new URLSearchParams(Object.entries(obj).filter(([, v]) => v !== "" && v !== undefined));
  function table(id, columns, rows, choose) {
    const host = $(id); host.replaceChildren();
    if (!rows.length) { host.append(text("p", "조회 결과가 없습니다.", "empty")); return; }
    const wrap = text("div", "", "table-wrap"), t = document.createElement("table"), head = document.createElement("thead"), tr = document.createElement("tr");
    columns.forEach(([label]) => tr.append(text("th", label))); if (choose) tr.append(text("th", "작업")); head.append(tr); t.append(head);
    const body = document.createElement("tbody");
    rows.forEach(row => { const r = document.createElement("tr"); columns.forEach(([, get]) => r.append(text("td", get(row))));
      if (choose) { const td = document.createElement("td"), b = text("button", "조회"); b.type = "button"; b.addEventListener("click", async () => { try { await choose(row); } catch (e) { notify(e.message, true); } }); td.append(b); r.append(td); } body.append(r); });
    t.append(body); wrap.append(t); host.append(wrap);
  }
  async function paged(id, path, params, columns, choose, page = 0) {
    const result = await request(path + query({ ...params, page, size: 20 }));
    table(id, columns, result.content, choose);
    const controls = text("div", "", "pagination");
    for (const [label, target, disabled] of [["이전", page - 1, result.first], ["다음", page + 1, result.last]]) {
      const b = text("button", label); b.type = "button"; b.disabled = disabled;
      b.addEventListener("click", () => paged(id, path, params, columns, choose, target).catch(e => notify(e.message, true))); controls.append(b);
    }
    controls.append(text("span", (page + 1) + " 페이지 / " + result.totalElements + "건")); $(id).append(controls);
  }
  function detail(id, pairs) { const d = document.createElement("dl"); pairs.forEach(([k, v]) => d.append(text("dt", k), text("dd", v))); $(id).replaceChildren(d); }
  function countDetail(c) {
    countId = c.id; set("count-load", "id", c.id);
    detail("count-detail", [["실사 번호", c.id], ["상태", state(c.status)], ["기준 시각", c.startedAt], ["기준 수량", c.baseOnHand], ["측정 수량", c.countedQuantity], ["조정 수량", c.adjustment], ["사유", c.approvalReason || c.reason]]);
  }
  async function loadCount(id) { countDetail(await request("/counts/" + encodeURIComponent(id))); location.hash = "count"; }
  async function loadOutbound(id) {
    outbound = await request("/outbounds/" + encodeURIComponent(id)); set("outbound-load", "id", id);
    detail("outbound-detail", [["지시 번호", id], ["창고", outbound.outbound.warehouseId], ["상태", state(outbound.outbound.status)], ["로트 · 수량", outbound.lines.map(l => l.lotId + " / " + l.quantity).join(", ")]]); location.hash = "outbound";
  }
  async function loadTransfer(id) {
    const result = await request("/transfers/" + encodeURIComponent(id)); transferId = id; set("transfer-load", "id", id);
    if (result.lines.length === 1) set("arrival-form", "lotId", result.lines[0].line.lotId);
    detail("transfer-detail", [["이동 번호", id], ["상태", state(result.transfer.status)], ["도착 예정", result.transfer.expectedArrivalAt], ["미도착 수량", result.lines.map(l => l.line.lotId + " / " + l.inTransitQuantity).join(", ")]]); location.hash = "transfer";
  }
  async function loadPurchase(id) {
    const result = await request("/purchase-orders/" + encodeURIComponent(id)); set("receipt-form", "purchaseOrderId", id);
    const select = field("receipt-form", "purchaseLineId"); select.replaceChildren(); result.lines.forEach(l => { const option = text("option", l.skuId + " / 미입고 " + (BigInt(l.orderedQuantity) - BigInt(l.receivedQuantity))); option.value = l.id; select.append(option); });
    detail("inbound-result", [["발주 번호", id], ["공급사", result.order.supplierId], ["창고", result.order.warehouseId], ["상태", state(result.status)]]); location.hash = "inbound";
  }
  bind("login-form", async () => {
    token = value("login-form", "token");
    await request("/skus?size=1");
    for (const [path, id] of [["skus", "skus"], ["warehouses", "warehouses"], ["suppliers", "suppliers"]]) {
      const result = await request("/" + path + "?size=200"); $(id).replaceChildren(); result.content.forEach(row => { const option = text("option", row.name); option.value = row.id; $(id).append(option); });
    }
    field("login-form", "token").value = ""; $("login-panel").hidden = true; $("workspace").hidden = false; $("logout").hidden = false;
  });
  click("logout", () => { disconnect(); notify("연결을 종료했습니다."); });
  bind("stock-form", async () => {
    const skuId = value("stock-form", "skuId"), warehouseId = value("stock-form", "warehouseId");
    const rows = await request("/stock/summary" + query({ skuId, warehouseId })); $("stock-summary").replaceChildren();
    for (const [key, label] of [["onHand", "보유"], ["available", "판매 가능"], ["reserved", "예약"], ["allocated", "할당"], ["blocked", "판매불가"], ["transferHeld", "이동 확보"]]) { const tile = text("div", "", "metric"); tile.append(text("span", label), text("strong", rows.reduce((sum, r) => sum + BigInt(r[key]), 0n).toString())); $("stock-summary").append(tile); }
    await paged("stock-table", "/stock", { skuId, warehouseId }, [["창고", r => r.balance.warehouseId], ["로트", r => r.lot.code], ["기한", r => r.lot.expiresOn], ["품질", r => state(r.lot.quality)], ["보유", r => r.balance.onHand], ["가용", r => r.available]], r => { set("count-start", "warehouseId", r.balance.warehouseId); set("count-start", "lotId", r.lot.id); set("transfer-form", "sourceWarehouseId", r.balance.warehouseId); set("transfer-form", "lotId", r.lot.id); location.hash = "count"; });
  });
  bind("purchase-form", async () => { const f = "purchase-form"; const r = await request("/purchase-orders/" + encodeURIComponent(value(f, "id")), "PUT", { supplierId: value(f, "supplierId"), warehouseId: value(f, "warehouseId"), lines: [{ skuId: value(f, "skuId"), quantity: number(f, "quantity", 1), unitCost: number(f, "unitCost", 1) }] }); await loadPurchase(r.order.id); });
  click("load-purchase", () => loadPurchase(value("receipt-form", "purchaseOrderId")));
  bind("receipt-form", async () => { const f = "receipt-form"; const r = await request("/receipts/" + encodeURIComponent(value(f, "id")), "PUT", { purchaseOrderId: value(f, "purchaseOrderId"), reason: value(f, "reason"), lines: [{ purchaseLineId: value(f, "purchaseLineId"), lotCode: value(f, "lotCode"), expiresOn: value(f, "expiresOn"), quality: value(f, "quality"), quantity: number(f, "quantity", 1) }] }); detail("inbound-result", [["입고 번호", r.receipt.id], ["매입 금액", r.purchaseAmount], ["입고 품목", r.lines.map(l => l.line.id).join(", ")], ["로트", r.lines.map(l => l.lot.id).join(", ")]]); set("inspection-form", "receiptLineId", r.lines[0].line.id); });
  bind("inspection-form", async () => { const f = "inspection-form"; const r = await request("/inspections/" + encodeURIComponent(value(f, "id")), "PUT", { receiptLineId: value(f, "receiptLineId"), goodQuantity: number(f, "goodQuantity"), damagedQuantity: number(f, "damagedQuantity"), reason: value(f, "reason") }); detail("inbound-result", [["검수 번호", r.id], ["정상", r.goodQuantity], ["불량", r.damagedQuantity]]); });
  const docCols = [["문서 번호", r => r.id], ["작업자", r => r.actorId], ["시각", r => r.createdAt || r.confirmedAt]];
  click("list-purchases", () => paged("inbound-table", "/operations/purchase-orders", {}, docCols, r => loadPurchase(r.id)));
  click("list-receipts", () => paged("inbound-table", "/operations/receipts", {}, docCols, async r => { const d = await request("/receipts/" + r.id); detail("inbound-result", [["입고 번호", d.receipt.id], ["매입 금액", d.purchaseAmount], ["입고 품목", d.lines.map(l => l.line.id).join(", ")]]); }));
  bind("outbound-load", () => loadOutbound(value("outbound-load", "id")));
  bind("outbound-form", async () => { if (!outbound) throw new Error("출고 지시를 먼저 조회하세요."); const f = "outbound-form"; const lines = outbound.lines.map(l => { const quantity = Number(l.quantity); if (!Number.isSafeInteger(quantity)) throw new Error("이 수량은 API에서 처리하세요."); return { lotId: l.lotId, quantity }; }); await request("/outbounds/" + outbound.outbound.id + "/" + value(f, "action"), "POST", { reason: value(f, "reason"), lines }); await loadOutbound(outbound.outbound.id); });
  bind("transfer-form", async () => { const f = "transfer-form", id = value(f, "id"); await request("/transfers/" + encodeURIComponent(id), "PUT", { sourceWarehouseId: value(f, "sourceWarehouseId"), destinationWarehouseId: value(f, "destinationWarehouseId"), expectedArrivalAt: new Date(value(f, "expectedArrivalAt")).toISOString(), reason: value(f, "reason"), lines: [{ lotId: value(f, "lotId"), quantity: number(f, "quantity", 1) }] }); await loadTransfer(id); });
  bind("transfer-load", () => loadTransfer(value("transfer-load", "id")));
  bind("transfer-action", async () => { if (!transferId) throw new Error("이동을 먼저 조회하세요."); await request("/transfers/" + transferId + "/" + value("transfer-action", "action"), "PUT", { reason: value("transfer-action", "reason") }); await loadTransfer(transferId); });
  bind("arrival-form", async () => { if (!transferId) throw new Error("이동을 먼저 조회하세요."); const f = "arrival-form"; await request("/transfers/" + transferId + "/receipts/" + encodeURIComponent(value(f, "id")), "PUT", { reason: value(f, "reason"), lines: [{ lotId: value(f, "lotId"), quantity: number(f, "quantity", 1) }] }); await loadTransfer(transferId); });
  bind("count-start", async () => { const f = "count-start"; countDetail(await request("/counts/" + encodeURIComponent(value(f, "id")), "PUT", { warehouseId: value(f, "warehouseId"), lotId: value(f, "lotId"), reason: value(f, "reason"), reconciliationId: value(f, "reconciliationId") || null })); });
  bind("count-load", () => loadCount(value("count-load", "id")));
  bind("count-measure", async () => { if (!countId) throw new Error("실사를 먼저 시작하거나 조회하세요."); countDetail(await request("/counts/" + countId + "/measurement", "PUT", { countedQuantity: number("count-measure", "countedQuantity"), reason: value("count-measure", "reason") })); });
  bind("count-action", async () => { if (!countId) throw new Error("실사를 먼저 조회하세요."); countDetail(await request("/counts/" + countId + "/" + value("count-action", "action"), "PUT", { reason: value("count-action", "reason") })); });
  bind("reconciliation-form", async () => { const f = "reconciliation-form"; const result = await request("/reconciliations/" + encodeURIComponent(value(f, "id")), "PUT", { skuId: value(f, "skuId"), warehouseId: value(f, "warehouseId") || null, reason: value(f, "reason"), page: number(f, "page"), size: 50, previousReportId: value(f, "previousReportId") || null });
    table("reconciliation-result", [["창고", r => r.warehouseId], ["로트", r => r.lotId], ["결과", r => r.matched ? "일치" : "조사 필요"], ["보유 / 원장", r => r.onHand + " / " + r.ledgerOnHand], ["예약 / 원장", r => r.reserved + " / " + r.ledgerReserved], ["할당 / 원장", r => r.allocated + " / " + r.ledgerAllocated], ["판매불가 / 원장", r => r.blocked + " / " + r.ledgerBlocked], ["이동 확보 / 원장", r => r.transferHeld + " / " + r.ledgerTransferHeld]], result.rows);
    $("reconciliation-result").prepend(text("p", "보고서 " + result.report.id + " · 이 페이지의 불일치 " + result.report.mismatchCount + "건" + (result.report.hasNext ? " · 다음 페이지가 있습니다. 새 번호로 다음 페이지를 확인하세요." : ""))); });
  bind("operations-form", async () => { const warehouseId = value("operations-form", "warehouseId"), cols = [["번호", r => r.id], ["창고", r => r.warehouseId || r.sourceWarehouseId], ["상태", r => state(r.status)]];
    await paged("reservation-table", "/operations/reservations", { warehouseId }, cols.concat([["기한", r => r.expiresAt]]));
    await paged("outbound-table", "/operations/outbounds", { warehouseId }, cols, r => loadOutbound(r.id));
    await paged("transfer-table", "/transfers", { warehouseId, incompleteOnly: true }, cols.concat([["도착 예정", r => r.expectedArrivalAt]]), r => loadTransfer(r.id));
    await paged("count-table", "/counts", { warehouseId, incompleteOnly: true }, cols, r => loadCount(r.id));
  });
  for (const b of document.querySelectorAll("[data-new]")) b.addEventListener("click", () => {
    const f = $(b.dataset.new); f.reset(); f.elements.namedItem("id").value = crypto.randomUUID();
    if (f.id === "count-start") { countId = ""; $("count-detail").replaceChildren(); set("count-load", "id", ""); }
    if (f.id === "transfer-form") { transferId = ""; $("transfer-detail").replaceChildren(); set("transfer-load", "id", ""); }
  });
  for (const id of ["purchase-form", "receipt-form", "inspection-form", "transfer-form", "arrival-form", "count-start", "reconciliation-form"]) set(id, "id", crypto.randomUUID());
})();
