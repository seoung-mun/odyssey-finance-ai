import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, type ApiClient } from "./api";
import { formatMoneyCompact } from "./formatMoney";
import {
  parseCategorySpending, parseImport, parseMonthlySpending, parseScheduledExpense, parseScheduledExpenses, parseTransactionPage,
  type CategorySpending, type MonthlySpending, type ScheduledExpense, type TransactionPage,
} from "./types";

type Form = { transactionAt: string; amount: string; category: string; transactionType: "PAYMENT" | "REFUND"; paymentSourceId: string; paymentExternalTransactionId: string; allocationAmount: string };
const empty: Form = { transactionAt: "", amount: "", category: "", transactionType: "PAYMENT", paymentSourceId: "", paymentExternalTransactionId: "", allocationAmount: "" };
type Filter = { from: string; to: string; category: string; type: "ALL" | "PAYMENT" | "REFUND" };
type Area = "ledger" | "monthly" | "categories" | "scheduled";
const emptyFilter: Filter = { from: "", to: "", category: "", type: "ALL" };
const areaDefaults = { ledger: false, monthly: false, categories: false, scheduled: false };
const errorDefaults = { ledger: "", monthly: "", categories: "", scheduled: "" };
const errorMessage = (e: ApiError) => e.status === 401 ? "로그인이 만료되었습니다. 다시 로그인해 주세요." : e.status === 409 ? "다른 변경이 반영되었습니다. 최신 상태를 다시 불러와 주세요." : e.status === 422 ? "입력값을 확인해 주세요." : e.status === 503 ? "서비스가 준비 중입니다. 잠시 후 다시 시도해 주세요." : e.status === 0 ? "네트워크 연결을 확인해 주세요." : "요청을 처리하지 못했습니다.";
const path = (filter: Filter, cursor?: string | null) => {
  const params = new URLSearchParams();
  if (filter.from) params.set("from", `${filter.from}T00:00:00+09:00`);
  if (filter.to) params.set("to", `${filter.to}T23:59:59+09:00`);
  if (filter.category.trim()) params.set("category", filter.category.trim());
  if (cursor) params.set("cursor", cursor);
  params.set("limit", "50");
  return `/transactions?${params}`;
};

export const TransactionsPage = ({ api }: { api: Pick<ApiClient, "get" | "post" | "patch"> }) => {
  const [ledger, setLedger] = useState<TransactionPage | null>(null);
  const [monthly, setMonthly] = useState<MonthlySpending[]>([]);
  const [categories, setCategories] = useState<CategorySpending | null>(null);
  const [scheduled, setScheduled] = useState<ScheduledExpense[]>([]);
  const [filter, setFilter] = useState<Filter>(emptyFilter);
  const [activeFilter, setActiveFilter] = useState<Filter>(emptyFilter);
  const [areaLoading, setAreaLoading] = useState(areaDefaults);
  const [areaErrors, setAreaErrors] = useState(errorDefaults);
  const [mutationError, setMutationError] = useState("");
  const [notice, setNotice] = useState("");
  const [transaction, setTransaction] = useState<Form>(empty);
  const [schedule, setSchedule] = useState({ name: "", amount: "", date: "" });
  const [editing, setEditing] = useState<ScheduledExpense | null>(null);
  const busyRef = useRef(false);
  const sequenceRef = useRef({ ledger: 0, monthly: 0, categories: 0, scheduled: 0 });

  const start = (area: Area) => {
    const sequence = ++sequenceRef.current[area];
    setAreaLoading((current) => ({ ...current, [area]: true }));
    setAreaErrors((current) => ({ ...current, [area]: "" }));
    return sequence;
  };
  const fail = (area: Area, reason: unknown) => {
    const error = reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR");
    setAreaErrors((current) => ({ ...current, [area]: errorMessage(error) }));
  };
  const finish = (area: Area, sequence: number) => {
    if (sequence === sequenceRef.current[area])
      setAreaLoading((current) => ({ ...current, [area]: false }));
  };
  const loadLedger = useCallback(async (nextFilter: Filter, cursor?: string | null, append = false) => {
    const sequence = start("ledger");
    try {
      const page = await api.get(path(nextFilter, cursor), parseTransactionPage);
      if (sequence !== sequenceRef.current.ledger) return;
      const parsed = parseTransactionPage(page);
      setLedger(current => append && current ? {
        items: [...current.items, ...parsed.items.filter(item => !current.items.some(existing => existing.id === item.id))],
        nextCursor: parsed.nextCursor,
      } : parsed);
    } catch (reason) { if (sequence === sequenceRef.current.ledger) fail("ledger", reason); }
    finally { finish("ledger", sequence); }
  }, [api]);
  const loadMonthly = useCallback(async () => {
    const sequence = start("monthly");
    try {
      const value = await api.get("/transactions/monthly-summary?months=6", parseMonthlySpending);
      if (sequence === sequenceRef.current.monthly) setMonthly(parseMonthlySpending(value));
    } catch (reason) { if (sequence === sequenceRef.current.monthly) fail("monthly", reason); }
    finally { finish("monthly", sequence); }
  }, [api]);
  const loadCategories = useCallback(async () => {
    const sequence = start("categories");
    try {
      const value = await api.get("/transactions/category-summary?months=3", parseCategorySpending);
      if (sequence === sequenceRef.current.categories) setCategories(parseCategorySpending(value));
    } catch (reason) { if (sequence === sequenceRef.current.categories) fail("categories", reason); }
    finally { finish("categories", sequence); }
  }, [api]);
  const loadScheduled = useCallback(async () => {
    const sequence = start("scheduled");
    try {
      const value = await api.get("/scheduled-expenses?status=PLANNED", parseScheduledExpenses);
      if (sequence === sequenceRef.current.scheduled) setScheduled(parseScheduledExpenses(value));
    } catch (reason) { if (sequence === sequenceRef.current.scheduled) fail("scheduled", reason); }
    finally { finish("scheduled", sequence); }
  }, [api]);
  const load = useCallback(async (nextFilter: Filter) => {
    await Promise.allSettled([
      loadLedger(nextFilter),
      loadMonthly(),
      loadCategories(),
      loadScheduled(),
    ]);
  }, [loadCategories, loadLedger, loadMonthly, loadScheduled]);
  useEffect(() => {
    const sequences = sequenceRef.current;
    void load(emptyFilter);
    return () => { for (const area of Object.keys(sequences) as Area[]) sequences[area] += 1; };
  }, [load]);

  const submitImport = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busyRef.current) return;
    const amount = Number(transaction.amount), allocation = Number(transaction.allocationAmount || 0);
    if (!Number.isSafeInteger(amount) || amount < 1 || !transaction.transactionAt || !transaction.category.trim()) return setMutationError("거래 시각, 금액, 분류를 확인해 주세요.");
    if (transaction.transactionType === "REFUND" && (!transaction.paymentSourceId.trim() || !transaction.paymentExternalTransactionId.trim() || allocation < 1)) return setMutationError("환불은 연결할 결제 source ID, 외부 거래 ID, 금액이 필요합니다.");
    if (allocation > amount) return setMutationError("환불 연결 금액은 환불 금액 이하여야 합니다.");
    busyRef.current = true; setMutationError(""); setNotice("");
    try {
      const result = parseImport(await api.post("/transactions/import", { transactions: [{
        transactionAt: new Date(transaction.transactionAt).toISOString(), amount, transactionType: transaction.transactionType, category: transaction.category.trim(), sourceId: "MANUAL", externalTransactionId: globalThis.crypto?.randomUUID?.() ?? `manual-${Date.now()}`,
        ...(transaction.transactionType === "REFUND" ? { refundAllocations: [{ paymentSourceId: transaction.paymentSourceId.trim(), paymentExternalTransactionId: transaction.paymentExternalTransactionId.trim(), amount: allocation }] } : {}),
      }] }, parseImport));
      setNotice(`거래 ${result.inserted}건을 반영했습니다. 중복 ${result.skipped}건은 건너뛰었습니다.`); setTransaction(empty); await load(activeFilter);
    } catch (reason) { setMutationError(errorMessage(reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR"))); }
    finally { busyRef.current = false; }
  };
  const createSchedule = async (event: React.FormEvent) => {
    event.preventDefault(); if (busyRef.current) return;
    const amount = Number(schedule.amount);
    if (!schedule.name.trim() || !Number.isSafeInteger(amount) || amount < 1 || !schedule.date) return setMutationError("예정지출 이름, 금액, 날짜를 확인해 주세요.");
    busyRef.current = true; setMutationError("");
    try { await api.post("/scheduled-expenses", { name: schedule.name.trim(), amount, scheduledDate: schedule.date }, parseScheduledExpense); setSchedule({ name: "", amount: "", date: "" }); setNotice("예정지출을 추가했습니다."); await load(activeFilter); }
    catch (reason) { setMutationError(errorMessage(reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR"))); }
    finally { busyRef.current = false; }
  };
  const cancelSchedule = async (id: number) => {
    if (busyRef.current) return; busyRef.current = true; setMutationError("");
    try { await api.patch(`/scheduled-expenses/${id}`, { status: "CANCELLED" }, parseScheduledExpense); setNotice("예정지출을 취소했습니다."); await load(activeFilter); }
    catch (reason) { setMutationError(errorMessage(reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR"))); }
    finally { busyRef.current = false; }
  };
  const saveSchedule = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!editing || busyRef.current) return;
    const amount = Number(editing.amount);
    if (!editing.name.trim() || !Number.isSafeInteger(amount) || amount < 1 || !editing.scheduledDate)
      return setMutationError("예정지출 이름, 금액, 날짜를 확인해 주세요.");
    busyRef.current = true; setMutationError("");
    try {
      await api.patch(`/scheduled-expenses/${editing.id}`, { name: editing.name.trim(), amount, scheduledDate: editing.scheduledDate }, parseScheduledExpense);
      setEditing(null); setNotice("예정지출을 수정했습니다."); await load(activeFilter);
    } catch (reason) { setMutationError(errorMessage(reason instanceof ApiError ? reason : new ApiError(0, "NETWORK_ERROR"))); }
    finally { busyRef.current = false; }
  };
  const update = <K extends keyof Form>(key: K, value: Form[K]) => setTransaction(current => ({ ...current, [key]: value }));

  const loading = Object.values(areaLoading).some(Boolean);
  return <main className="dashboard-shell" aria-busy={loading}>
    <section aria-label="거래 원장"><p className="eyebrow">현금 흐름</p><h1>거래 원장</h1><form onSubmit={event => { event.preventDefault(); setActiveFilter(filter); void loadLedger(filter); }}><label>조회 시작일 <input type="date" value={filter.from} onChange={event => setFilter({ ...filter, from: event.target.value })} /></label><label>조회 종료일 <input type="date" value={filter.to} onChange={event => setFilter({ ...filter, to: event.target.value })} /></label><label>조회 거래 유형 <select value={filter.type} onChange={event => setFilter({ ...filter, type: event.target.value as Filter["type"] })}><option value="ALL">전체</option><option value="PAYMENT">결제</option><option value="REFUND">환불</option></select></label><label>카테고리 필터 <input value={filter.category} onChange={event => setFilter({ ...filter, category: event.target.value })} /></label><button className="secondary">필터 적용</button></form>
      {areaErrors.ledger && <p role="alert" className="notice danger">{areaErrors.ledger} <button onClick={() => void loadLedger(activeFilter)}>거래 원장 다시 불러오기</button></p>}
      {areaLoading.ledger && !ledger && <p role="status">거래 원장을 불러오고 있습니다.</p>}
      {ledger?.items.filter(item => activeFilter.type === "ALL" || item.transactionType === activeFilter.type).length ? <table><thead><tr><th>시각</th><th>유형</th><th>분류</th><th>상호</th><th>금액</th></tr></thead><tbody>{ledger.items.filter(item => activeFilter.type === "ALL" || item.transactionType === activeFilter.type).map(item => <tr key={item.id}><td>{item.transactionAt}</td><td>{item.transactionType}</td><td>{item.category}</td><td>{item.merchantName ?? "-"}</td><td>{formatMoneyCompact(item.amount)}</td></tr>)}</tbody></table> : <p>조건에 맞는 거래가 없습니다.</p>}
      {ledger?.nextCursor && <button className="secondary" disabled={areaLoading.ledger} onClick={() => void loadLedger(activeFilter, ledger.nextCursor, true)}>다음 거래 불러오기</button>}</section>
    <section aria-label="월별 소비 요약"><h2>월별 소비 요약</h2>{areaErrors.monthly ? <p role="alert" className="notice danger">{areaErrors.monthly} <button onClick={() => void loadMonthly()}>월별 소비 다시 불러오기</button></p> : areaLoading.monthly && !monthly.length ? <p role="status">월별 소비를 불러오고 있습니다.</p> : monthly.length ? <ul>{monthly.map(item => <li key={item.yearMonth}>{item.yearMonth}: {formatMoneyCompact(item.adjustedConsumption)}</li>)}</ul> : <p>집계할 소비 내역이 없습니다.</p>}</section>
    <section aria-label="카테고리별 월평균"><h2>카테고리별 월평균</h2>{areaErrors.categories ? <p role="alert" className="notice danger">{areaErrors.categories} <button onClick={() => void loadCategories()}>카테고리 요약 다시 불러오기</button></p> : areaLoading.categories && !categories ? <p role="status">카테고리 요약을 불러오고 있습니다.</p> : categories ? <><p>전체 평균 {formatMoneyCompact(categories.currentAvgVariableSpending)}</p><ul>{categories.categories.map(item => <li key={item.category}>{item.category}: {formatMoneyCompact(item.monthlyAverage)}</li>)}</ul></> : <p>집계할 카테고리가 없습니다.</p>}</section>
    <section aria-label="거래 가져오기"><h2>거래 가져오기</h2><form onSubmit={submitImport}><label>거래 시각 <input type="datetime-local" value={transaction.transactionAt} onChange={event => update("transactionAt", event.target.value)} /></label><label>거래 금액 <input inputMode="numeric" value={transaction.amount} onChange={event => update("amount", event.target.value)} /></label><label>거래 분류 <input value={transaction.category} onChange={event => update("category", event.target.value)} /></label><label>거래 유형 <select value={transaction.transactionType} onChange={event => update("transactionType", event.target.value as Form["transactionType"])}><option value="PAYMENT">PAYMENT</option><option value="REFUND">REFUND</option></select></label>{transaction.transactionType === "REFUND" && <><label>결제 source ID <input value={transaction.paymentSourceId} onChange={event => update("paymentSourceId", event.target.value)} /></label><label>결제 외부 거래 ID <input value={transaction.paymentExternalTransactionId} onChange={event => update("paymentExternalTransactionId", event.target.value)} /></label><label>환불 연결 금액 <input inputMode="numeric" value={transaction.allocationAmount} onChange={event => update("allocationAmount", event.target.value)} /></label></>}<button className="primary" disabled={loading}>거래 가져오기</button></form></section>
    <section aria-label="예정지출"><h2>예정지출</h2>{areaErrors.scheduled && <p role="alert" className="notice danger">{areaErrors.scheduled} <button onClick={() => void loadScheduled()}>예정지출 다시 불러오기</button></p>}<form onSubmit={createSchedule}><label>예정지출 이름 <input value={schedule.name} onChange={event => setSchedule({ ...schedule, name: event.target.value })} /></label><label>예정지출 금액 <input inputMode="numeric" value={schedule.amount} onChange={event => setSchedule({ ...schedule, amount: event.target.value })} /></label><label>예정지출 날짜 <input type="date" value={schedule.date} onChange={event => setSchedule({ ...schedule, date: event.target.value })} /></label><button className="primary" disabled={areaLoading.scheduled}>예정지출 추가</button></form>{editing && <form onSubmit={saveSchedule}><label>{editing.name} 이름 <input value={editing.name} onChange={event => setEditing({ ...editing, name: event.target.value })} /></label><label>{editing.name} 금액 <input inputMode="numeric" value={editing.amount} onChange={event => setEditing({ ...editing, amount: Number(event.target.value) })} /></label><label>{editing.name} 날짜 <input type="date" value={editing.scheduledDate} onChange={event => setEditing({ ...editing, scheduledDate: event.target.value })} /></label><button className="primary">{editing.name} 저장</button></form>}{areaLoading.scheduled && !scheduled.length ? <p role="status">예정지출을 불러오고 있습니다.</p> : scheduled.length ? <ul>{scheduled.map(item => <li key={item.id}>{item.name} {formatMoneyCompact(item.amount)} <button className="secondary" disabled={areaLoading.scheduled} onClick={() => setEditing(item)}>{item.name} 수정</button> <button className="secondary" disabled={areaLoading.scheduled} onClick={() => void cancelSchedule(item.id)}>취소</button></li>)}</ul> : !areaErrors.scheduled && <p>등록된 예정지출이 없습니다.</p>}</section>
    {mutationError && <p role="alert" className="notice danger">{mutationError}</p>}{notice && <p role="status" className="notice">{notice}</p>}
  </main>;
};
