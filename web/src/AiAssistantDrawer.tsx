import { useEffect, useRef, useState } from "react";
import { ApiError, type ApiClient } from "./api";
import { formatMoneyCompact } from "./formatMoney";
import {
  parseChatResponse,
  parseSavingsWhatIfResponse,
  type ChatIntent,
  type ChatSelection,
  type SavingsRecommendationResponse,
  type SavingsWhatIfResponse,
} from "./types";

type ChatMessage = {
  id: number;
  role: "user" | "assistant";
  text: string;
  intent?: ChatIntent;
  savings?: SavingsRecommendationResponse | null;
  whatIf?: SavingsWhatIfResponse | null;
};

const prompts = [
  { label: "내게 맞는 주거정책 찾아줘", message: "내게 맞는 주거정책 찾아줘" },
  { label: "내 계획에 맞는 적금 추천해줘", message: "현재 계획에 맞는 적금 추천해줘" },
  { label: "내 계획 점검해줘", message: "내 계획 상태 알려줘" },
];

export type AssistantPlanContext = {
  goalName: string;
  currentSavedAmount: number;
  targetAmount: number;
  targetDate: string;
  monthlySpending: number | null;
  stability: number | null;
};

export const AiAssistantDrawer = ({
  api,
  open,
  context,
  showPlanOverview,
  onClose,
  onOpenPolicy,
  onOpenTransactions,
  onRequestReplan,
}: {
  api: Pick<ApiClient, "post">;
  open: boolean;
  context: AssistantPlanContext;
  showPlanOverview: boolean;
  onClose: () => void;
  onOpenPolicy: () => void;
  onOpenTransactions: () => void;
  onRequestReplan: () => Promise<boolean>;
}) => {
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sessionMode, setSessionMode] = useState<"STATEFUL" | "STATELESS_FALLBACK" | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [selection, setSelection] = useState<ChatSelection | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const sequence = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose, open]);

  const deterministicPlanStatus = () => {
    const remaining = Math.max(context.targetAmount - context.currentSavedAmount, 0);
    const plan = context.monthlySpending === null
      ? "선택된 월 유동지출 계획은 아직 없습니다."
      : `현재 월 유동지출 한도는 ${formatMoneyCompact(context.monthlySpending)}입니다.`;
    return `${context.goalName} 목표일까지 ${formatMoneyCompact(remaining)}이 남아 있어요. ${plan}`;
  };

  const send = async (message = draft, displayMessage = message) => {
    const trimmed = message.trim();
    if (!trimmed || sending) return;
    const userId = ++sequence.current;
    setMessages((current) => [...current, { id: userId, role: "user", text: displayMessage.trim() }]);
    setDraft("");
    setSending(true);
    setError("");
    try {
      const response = parseChatResponse(await api.post(
        "/chat/messages",
        { sessionId, message: trimmed, selection },
        parseChatResponse,
      ));
      setSessionId(response.sessionId);
      setSessionMode(response.sessionMode);
      let text = response.message;
      let whatIf: SavingsWhatIfResponse | null = null;
      if (response.intent === "PLAN_STATUS") text = deterministicPlanStatus();
      if (response.intent === "POLICY_SEARCH") text = "정책 탐색을 시작할게요.";
      if (response.intent === "SAVINGS_WHAT_IF") {
        if (selection?.productId && selection.optionId) {
          try {
            whatIf = parseSavingsWhatIfResponse(await api.post(
              `/savings/products/${selection.productId}/what-if`,
              { optionId: selection.optionId, conditionIds: selection.conditionIds ?? [] },
              parseSavingsWhatIfResponse,
            ));
            text = whatIf.calculable ? "선택한 우대조건을 적용한 예상 결과예요." : whatIf.message ?? response.message;
          } catch (reason) {
            text = reason instanceof ApiError ? reason.message : "우대조건 결과를 계산하지 못했습니다.";
          }
        } else {
          text = "먼저 추천 상품을 선택한 뒤 우대조건을 확인해 주세요.";
        }
      }
      const assistantId = ++sequence.current;
      setMessages((current) => [...current, {
        id: assistantId,
        role: "assistant",
        text,
        intent: response.intent,
        savings: response.savingsRecommendations,
        whatIf,
      }]);
      if (response.intent === "POLICY_SEARCH") {
        onClose();
        onOpenPolicy();
      }
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "메시지를 보내지 못했습니다.");
    } finally {
      setSending(false);
    }
  };

  const requestReplan = async () => {
    if (await onRequestReplan()) onClose();
  };

  if (!open) return null;
  return (
    <div className="assistant-drawer-layer">
      <aside className="assistant-drawer" aria-labelledby="assistant-drawer-title">
        <header className="dialog-heading assistant-drawer-header">
          <div><p className="eyebrow">✦ ODYSSEY AI</p><h2 id="assistant-drawer-title">계획과 금융을 함께 살펴봐요</h2></div>
          <button className="text-button" onClick={onClose}>닫기</button>
        </header>
        <div className="assistant-conversation">
          <p className="assistant-intro">계획을 점검하거나 정책, 적금에 대해 자유롭게 물어보세요.</p>
          {showPlanOverview && messages.length === 0 && (
            <section className="assistant-plan-overview" aria-label="현재 계획 점검">
              <span>현재 계획을 확인했어요</span>
              <h3>{context.goalName}</h3>
              <p>{deterministicPlanStatus()}</p>
              <dl>
                <div><dt>남은 금액</dt><dd>{formatMoneyCompact(Math.max(context.targetAmount - context.currentSavedAmount, 0))}</dd></div>
                <div><dt>월 유동지출 한도</dt><dd>{context.monthlySpending === null ? "선택 전" : formatMoneyCompact(context.monthlySpending)}</dd></div>
                <div><dt>목표일</dt><dd>{context.targetDate}</dd></div>
                <div><dt>계획 안정성</dt><dd>{context.stability === null ? "선택 전" : `${Math.round(context.stability * 100)}%`}</dd></div>
              </dl>
            </section>
          )}
          <div className="assistant-prompts" aria-label="빠른 질문">{prompts.map((prompt) => <button key={prompt.label} onClick={() => void send(prompt.message, prompt.label)}>{prompt.label}</button>)}</div>
          <div className="assistant-messages" aria-live="polite">
            {messages.map((message) => (
              <div key={message.id} className={`assistant-message ${message.role}`}>
                <p>{message.text}</p>
                {message.savings && message.savings.recommendations.length > 0 && (
                  <div className="assistant-savings-list">
                    {message.savings.recommendations.map((item) => (
                      <div key={item.optionId} className="assistant-savings-card">
                        <strong>{item.bankName} · {item.productName}</strong>
                        <span>{item.termMonths}개월 · 기본 {item.baseRate}% · 세전 {formatMoneyCompact(item.pretaxInterest)}</span>
                        <div className="assistant-condition-list">
                          {item.availableConditions.map((condition) => (
                            <label key={condition.conditionId}>
                              <input
                                type="checkbox"
                                checked={selection?.optionId === item.optionId && (selection.conditionIds ?? []).includes(condition.conditionId)}
                                onChange={(event) => {
                                  const current = selection?.optionId === item.optionId ? selection.conditionIds ?? [] : [];
                                  setSelection({
                                    productId: item.productId,
                                    optionId: item.optionId,
                                    conditionIds: event.target.checked
                                      ? [...current, condition.conditionId]
                                      : current.filter((id) => id !== condition.conditionId),
                                  });
                                }}
                              />
                              {condition.label} (+{condition.bonusRate}%p)
                            </label>
                          ))}
                        </div>
                        <button className="text-button" onClick={() => setSelection({ productId: item.productId, optionId: item.optionId, conditionIds: [] })}>이 상품 선택</button>
                      </div>
                    ))}
                  </div>
                )}
                {message.whatIf?.calculable && <dl className="assistant-what-if"><div><dt>적용금리</dt><dd>{message.whatIf.appliedRate === null ? "계산값 없음" : `${message.whatIf.appliedRate}%`}</dd></div><div><dt>예상 세전이자</dt><dd>{message.whatIf.pretaxInterest === null ? "계산값 없음" : formatMoneyCompact(message.whatIf.pretaxInterest)}</dd></div><div><dt>목표 도달 예상 변화</dt><dd>{message.whatIf.acceleratedMonths === null ? "계산값 없음" : `${message.whatIf.acceleratedMonths}개월`}</dd></div></dl>}
                {message.intent === "SPENDING_SUMMARY" && <button className="text-button" onClick={onOpenTransactions}>소비 내역 보기</button>}
                {message.intent === "REPLAN_GUIDE" && <button className="text-button" disabled={sending} onClick={() => void requestReplan()}>현재 시점 기준으로 다시 계산</button>}
              </div>
            ))}
            {sending && <p role="status" className="assistant-typing">답변을 준비하고 있습니다.</p>}
          </div>
        </div>
        {sessionMode === "STATELESS_FALLBACK" && <p className="assistant-session-note">대화 기록 없이 현재 요청을 처리하고 있어요.</p>}
        {error && <p role="alert" className="notice danger">{error}</p>}
        <form className="assistant-composer" onSubmit={(event) => { event.preventDefault(); void send(); }}>
          <label className="sr-only" htmlFor="assistant-message">메시지</label>
          <input id="assistant-message" ref={inputRef} maxLength={500} value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="궁금한 내용을 입력하세요" />
          <button className="primary" aria-label="보내기" disabled={sending || !draft.trim()}><span aria-hidden="true">↑</span></button>
        </form>
      </aside>
    </div>
  );
};
