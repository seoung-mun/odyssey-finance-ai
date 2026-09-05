const koreanInteger = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 });

export const formatMoneyCompact = (amount: number): string => {
  if (amount === 0) return "0원";
  const absolute = Math.abs(amount);
  if (absolute < 10_000) return `${koreanInteger.format(amount)}원`;
  const tenThousands = Math.floor(absolute / 10_000);
  return `${amount < 0 ? "-" : ""}${koreanInteger.format(tenThousands)}만원`;
};
