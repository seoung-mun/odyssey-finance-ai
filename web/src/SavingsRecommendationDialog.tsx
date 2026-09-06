import { useEffect, useRef, useState } from "react";
import { ApiError, type ApiClient } from "./api";
import { formatMoneyCompact } from "./formatMoney";
import {
  parseSavingsRecommendationResponse,
  parseSavingsWhatIfResponse,
  type SavingsRecommendation,
  type SavingsRecommendationResponse,
  type SavingsWhatIfResponse,
} from "./types";

type SavingsApi = Pick<ApiClient, "get" | "post">;

export const SavingsRecommendationDialog = ({
  api,
  open,
  onClose,
}: {
  api: SavingsApi;
  open: boolean;
  onClose: () => void;
}) => {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [data, setData] = useState<SavingsRecommendationResponse | null>(null);
  const [selected, setSelected] = useState<SavingsRecommendation | null>(null);
  const [conditionIds, setConditionIds] = useState<number[]>([]);
  const [whatIf, setWhatIf] = useState<SavingsWhatIfResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [whatIfLoading, setWhatIfLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!open) {
      if (dialog.open) dialog.close();
      return;
    }
    if (!dialog.open) {
      if (dialog.showModal) dialog.showModal();
      else dialog.setAttribute("open", "");
    }
    setData(null);
    setSelected(null);
    setConditionIds([]);
    setWhatIf(null);
    setError("");
    setLoading(true);
    let cancelled = false;
    void api.get("/savings/recommendations", parseSavingsRecommendationResponse)
      .then((value) => {
        if (!cancelled) setData(parseSavingsRecommendationResponse(value));
      })
      .catch((reason) => {
        if (cancelled) return;
        const apiError = reason instanceof ApiError ? reason : null;
        setError(apiError?.status === 422
          ? "현재 선택된 계획이 없어 적금 상품을 추천할 수 없습니다."
          : apiError?.message ?? "적금 추천을 불러오지 못했습니다.");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [api, open]);

  const close = () => {
    dialogRef.current?.close();
    onClose();
  };

  const calculateWhatIf = async () => {
    if (!selected || whatIfLoading) return;
    setWhatIfLoading(true);
    setError("");
    try {
      const value = await api.post(
        `/savings/products/${selected.productId}/what-if`,
        { optionId: selected.optionId, conditionIds },
        parseSavingsWhatIfResponse,
      );
      setWhatIf(parseSavingsWhatIfResponse(value));
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "우대조건 결과를 계산하지 못했습니다.");
    } finally {
      setWhatIfLoading(false);
    }
  };

  return (
    <dialog
      ref={dialogRef}
      className="dashboard-dialog savings-dialog odyssey-tool-dialog"
      aria-label="적금 상품 추천"
      onCancel={(event) => { event.preventDefault(); close(); }}
    >
      <div className="dialog-heading">
        <div><p className="eyebrow">✦ ODYSSEY AI</p><h2 id="savings-dialog-title">현재 계획으로 감당 가능한 적금을 비교했어요</h2></div>
        <button className="text-button" onClick={close}>닫기</button>
      </div>
      {loading && <p role="status" aria-busy="true">현재 계획에 맞는 상품을 찾고 있습니다.</p>}
      {error && <p role="alert" className="notice danger">{error}</p>}
      {data && (
        <>
          <dl className="savings-plan-basis">
            <div><dt>현재 계획 기준 월 저축 가능액</dt><dd>{formatMoneyCompact(data.monthlySavings)}</dd></div>
            <div><dt>남은 계획 기간</dt><dd>{data.remainingMonths}개월</dd></div>
          </dl>
          {data.recommendations.length === 0 ? (
            <div className="tool-empty-state"><strong>현재 추천 가능한 적금 상품을 불러오지 못했어요.</strong><p>상품 데이터가 준비되면 다시 확인할 수 있어요.</p></div>
          ) : (
            <div className="savings-results" aria-label="추천 적금 Top 3">
              {data.recommendations.map((recommendation, index) => (
                <article key={recommendation.optionId} className="savings-product-card" data-featured={index === 0}>
                  <div className="savings-rank"><span>추천 {index + 1}</span>{index === 0 && <strong>가장 먼저 확인</strong>}</div>
                  <p className="savings-bank">{recommendation.bankName}</p>
                  <h3>{recommendation.productName}</h3>
                  <dl>
                    <div><dt>가입 기간</dt><dd>{recommendation.termMonths}개월</dd></div>
                    <div><dt>기본금리</dt><dd>{recommendation.baseRate}%</dd></div>
                    <div><dt>최대금리</dt><dd>{recommendation.maximumRate}%</dd></div>
                    <div><dt>예상 세전이자</dt><dd>{formatMoneyCompact(recommendation.pretaxInterest)}</dd></div>
                    <div><dt>목표 도달 예상 변화</dt><dd>{recommendation.acceleratedMonths}개월</dd></div>
                  </dl>
                  <button
                    type="button"
                    className="secondary"
                    onClick={() => {
                      setSelected(selected?.optionId === recommendation.optionId ? null : recommendation);
                      setConditionIds([]);
                      setWhatIf(null);
                    }}
                  >우대조건 확인</button>
                  {selected?.optionId === recommendation.optionId && (
                    <div className="savings-condition-panel">
                      {recommendation.availableConditions.length === 0 ? <p>자가 확인할 우대조건이 없습니다.</p> : recommendation.availableConditions.map((condition) => (
                        <label key={condition.conditionId}>
                          <input
                            type="checkbox"
                            checked={conditionIds.includes(condition.conditionId)}
                            onChange={(event) => setConditionIds((current) => event.target.checked
                              ? [...current, condition.conditionId]
                              : current.filter((id) => id !== condition.conditionId))}
                          />
                          <span>{condition.label} <small>+{condition.bonusRate}%p</small></span>
                        </label>
                      ))}
                      <button className="primary" disabled={whatIfLoading} onClick={() => void calculateWhatIf()}>{whatIfLoading ? "계산 중…" : "선택 조건으로 비교"}</button>
                      {whatIf && (
                        <div className="savings-comparison" role="status">
                          {!whatIf.calculable ? <p>{whatIf.message}</p> : <dl>
                            <div><dt>기본금리 → 적용금리</dt><dd>{recommendation.baseRate}% → {whatIf.appliedRate === null ? "계산값 없음" : `${whatIf.appliedRate}%`}</dd></div>
                            <div><dt>기존 → 조건 반영 예상 이자</dt><dd>{formatMoneyCompact(recommendation.pretaxInterest)} → {whatIf.pretaxInterest === null ? "계산값 없음" : formatMoneyCompact(whatIf.pretaxInterest)}</dd></div>
                            <div><dt>목표 도달 예상 변화</dt><dd>{whatIf.acceleratedMonths === null ? "계산값 없음" : `${whatIf.acceleratedMonths}개월`}</dd></div>
                          </dl>}
                        </div>
                      )}
                    </div>
                  )}
                </article>
              ))}
            </div>
          )}
          <div className="tool-disclaimer"><p>세전 기준이며 현재 계획은 변경되지 않습니다.</p><p>실제 가입 가능 여부와 우대금리는 금융회사 조건에 따라 달라질 수 있어요.</p></div>
        </>
      )}
    </dialog>
  );
};
