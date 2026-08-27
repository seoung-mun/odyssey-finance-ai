// Screen 10 — Recalculate Confirmation Modal
import { Modal, Button } from "../components/shared";

export default function Screen10RecalcModal({ open, onClose, onConfirm }: {
  open: boolean; onClose: () => void; onConfirm: () => void;
}) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="현재 시점 기준으로 다시 계산할까요?"
    >
      <p className="text-[14px] text-[#6B7280] leading-[1.7] mb-6">
        현재까지의 소비내역과 소비 변동패턴을 반영해 새로운 계획을 계산합니다.
        <br/><br/>
        새 계획을 선택하기 전까지 현재 계획은 그대로 유지됩니다.
      </p>

      <div className="bg-[#F5F6F9] rounded-[14px] p-4 mb-7">
        <p className="text-[11px] font-bold text-[#9CA3AF] uppercase tracking-wide mb-3">반영되는 정보</p>
        <div className="flex flex-col gap-2.5">
          {[
            "이번 달 현재까지 소비 430,000원",
            "최근 3개월 소비 패턴 변동",
            "목표까지 남은 기간 재산정",
            "예정 지출 2건 반영",
          ].map((item) => (
            <div key={item} className="flex items-center gap-2.5">
              <div className="w-1.5 h-1.5 rounded-full bg-[#4F58FF] flex-shrink-0"/>
              <span className="text-[13px] text-[#374151]">{item}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="flex gap-3">
        <Button variant="outline" className="flex-1" onClick={onClose}>취소</Button>
        <Button className="flex-1" onClick={onConfirm}>다시 계산</Button>
      </div>
    </Modal>
  );
}
