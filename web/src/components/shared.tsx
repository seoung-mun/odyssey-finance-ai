import React, { useState } from "react";

// ─── Design tokens (single source of truth) ────────────────────
export const tokens = {
  primary: "#4F58FF",
  primaryDark: "#3840E0",
  primaryLight: "#EEF0FF",
  primaryMid: "#C7CAFF",
  success: "#0DB97A",
  successBg: "#EDFAF4",
  successBorder: "#A3E8CC",
  warning: "#E09B00",
  warningBg: "#FFFBEB",
  warningBorder: "#FDE68A",
  warningText: "#92400E",
  error: "#E53E3E",
  errorBg: "#FFF5F5",
  errorBorder: "#FEB2B2",
  errorText: "#9B2C2C",
  surface: "#FFFFFF",
  surfaceAlt: "#F5F6F9",
  border: "#E8EAEF",
  borderStrong: "#D1D5DB",
  text: "#111827",
  textSecondary: "#4B5563",
  textMuted: "#9CA3AF",
  textOnPrimary: "#FFFFFF",
  radius: "14px",
  radiusSm: "10px",
  radiusLg: "20px",
  radiusXl: "24px",
};

// ─── Icon helpers ────────────────────────────────────────────────
export function IconCheck({ size = 16, color = "currentColor" }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none">
      <path d="M3 8l3.5 3.5L13 5" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}
export function IconArrowRight({ size = 16, color = "currentColor" }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none">
      <path d="M3.5 8h9M9 4.5l3.5 3.5L9 11.5" stroke={color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}
export function IconWarning({ size = 16, color = "#E09B00" }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none">
      <path d="M8 2.5L14.5 13.5H1.5L8 2.5z" stroke={color} strokeWidth="1.5" fill="none" strokeLinejoin="round"/>
      <path d="M8 7v3M8 11.5v.5" stroke={color} strokeWidth="1.5" strokeLinecap="round"/>
    </svg>
  );
}
export function IconCalendar({ size = 16, color = "currentColor" }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none">
      <rect x="1.5" y="3" width="13" height="11.5" rx="2.5" stroke={color} strokeWidth="1.4"/>
      <path d="M1.5 7h13M5 1.5v3M11 1.5v3" stroke={color} strokeWidth="1.4" strokeLinecap="round"/>
    </svg>
  );
}

// ─── Status Badge ───────────────────────────────────────────────
type BadgeVariant = "primary" | "success" | "warning" | "error" | "muted" | "active";
export function StatusBadge({ children, variant = "primary" }: { children: React.ReactNode; variant?: BadgeVariant }) {
  const styles: Record<BadgeVariant, string> = {
    primary: "bg-[#EEF0FF] text-[#3840E0]",
    success: "bg-[#EDFAF4] text-[#065F46]",
    warning: "bg-[#FFFBEB] text-[#92400E]",
    error: "bg-[#FFF5F5] text-[#9B2C2C]",
    muted: "bg-[#F3F4F6] text-[#6B7280]",
    active: "bg-[#4F58FF] text-white",
  };
  return (
    <span className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-semibold tracking-wide ${styles[variant]}`}>
      {children}
    </span>
  );
}

// ─── Primary / Secondary Buttons ────────────────────────────────
interface BtnProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary" | "ghost" | "danger" | "outline";
  size?: "xs" | "sm" | "md" | "lg";
  icon?: React.ReactNode;
  iconRight?: React.ReactNode;
  loading?: boolean;
}
export function Button({ variant = "primary", size = "md", className = "", children, icon, iconRight, loading, disabled, ...rest }: BtnProps) {
  const base = "inline-flex items-center justify-center gap-2 font-semibold rounded-[12px] cursor-pointer select-none disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F58FF] focus-visible:ring-offset-1";
  const vMap = {
    primary:   "bg-[#4F58FF] text-white hover:bg-[#3840E0] active:bg-[#2F35C8]",
    secondary: "bg-[#EEF0FF] text-[#3840E0] hover:bg-[#E2E5FF] active:bg-[#D4D8FF]",
    ghost:     "bg-transparent text-[#4B5563] hover:bg-[#F3F4F6] active:bg-[#E5E7EB]",
    danger:    "bg-transparent text-[#E53E3E] border border-[#FEB2B2] hover:bg-[#FFF5F5]",
    outline:   "bg-white text-[#374151] border border-[#E8EAEF] hover:border-[#C7CAFF] hover:text-[#4F58FF]",
  };
  const sMap = {
    xs: "px-3 py-1.5 text-xs",
    sm: "px-4 py-2 text-sm",
    md: "px-5 py-2.5 text-sm",
    lg: "px-7 py-3.5 text-[15px]",
  };
  return (
    <button className={`${base} ${vMap[variant]} ${sMap[size]} ${className}`} disabled={disabled || loading} {...rest}>
      {loading ? (
        <svg className="animate-spin" width="14" height="14" viewBox="0 0 14 14" fill="none">
          <circle cx="7" cy="7" r="5.5" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2"/>
          <path d="M7 1.5a5.5 5.5 0 015.5 5.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
        </svg>
      ) : icon}
      {children}
      {!loading && iconRight}
    </button>
  );
}

// ─── Input ──────────────────────────────────────────────────────
interface InputProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "prefix"> {
  label?: string; error?: string; hint?: string;
  prefix?: React.ReactNode; suffix?: React.ReactNode;
}
export function Input({ label, error, hint, prefix, suffix, className = "", id, ...rest }: InputProps) {
  const inputId = id || label?.replace(/\s/g, "-").toLowerCase();
  return (
    <div className="flex flex-col gap-1.5">
      {label && <label htmlFor={inputId} className="text-[13px] font-semibold text-[#374151]">{label}</label>}
      <div className="relative flex items-center">
        {prefix && <span className="absolute left-3.5 text-[#9CA3AF] pointer-events-none flex items-center">{prefix}</span>}
        <input
          id={inputId}
          className={`w-full rounded-[12px] border bg-white text-[14px] text-[#111827] placeholder:text-[#C0C7D0]
            px-3.5 py-2.5 focus:outline-none focus:ring-2 focus:ring-[#4F58FF]/30 focus:border-[#4F58FF]
            ${error ? "border-[#FEB2B2] focus:border-[#E53E3E] focus:ring-[#E53E3E]/20" : "border-[#E8EAEF] hover:border-[#C7CAFF]"}
            ${prefix ? "pl-9" : ""} ${suffix ? "pr-14" : ""}
            ${className}`}
          {...rest}
        />
        {suffix && <span className="absolute right-3.5 text-[13px] text-[#9CA3AF] pointer-events-none">{suffix}</span>}
      </div>
      {hint && !error && <p className="text-[12px] text-[#7C8594]">{hint}</p>}
      {error && <p className="text-[12px] text-[#E53E3E]">{error}</p>}
    </div>
  );
}

// ─── Currency Input ─────────────────────────────────────────────
export function CurrencyInput({ label, value, onChange, hint, error, placeholder = "0", ...rest }:
  { label?: string; value: string; onChange: (v: string) => void; hint?: string; error?: string; placeholder?: string }
  & Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "placeholder">) {
  const display = value ? Number(value).toLocaleString("ko-KR") : "";
  return (
    <Input
      label={label}
      value={display}
      suffix="원"
      hint={hint}
      error={error}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value.replace(/[^0-9]/g, ""))}
      className="num"
      {...rest}
    />
  );
}

// ─── Progress Stepper — nautical route ─────────────────────────
export function ProgressStepper({ steps, current }: { steps: string[]; current: number }) {
  // Solid-line route stepper. Ship marks the current step and transitions when `current` changes.
  const totalW = 480;
  const nodeR  = 11;
  const cy     = 22;
  const n      = steps.length;
  const nodeX  = (i: number) => (totalW / (n - 1)) * i;
  const shipX  = nodeX(Math.min(current, n - 1));

  return (
    <nav aria-label="진행 단계" style={{ width: totalW, height: 62, position: "relative" }}>
      <svg
        viewBox={`0 0 ${totalW} ${cy * 2}`}
        style={{ position: "absolute", top: 0, left: 0, width: totalW, height: cy * 2, overflow: "visible" }}
        fill="none"
      >
        {/* ── Track — full width solid gray ── */}
        <line x1={nodeX(0)} y1={cy} x2={nodeX(n - 1)} y2={cy}
          stroke="#E8EAEF" strokeWidth="2" strokeLinecap="round"/>

        {/* Completed portion — solid primary blue, transitions as current advances */}
        <line
          x1={nodeX(0)} y1={cy}
          x2={nodeX(Math.min(current, n - 1))} y2={cy}
          stroke="#4F58FF" strokeWidth="2" strokeLinecap="round"
          style={{ transition: "x2 0.5s ease" }}
        />

        {/* ── Step nodes ── */}
        {steps.map((_, i) => {
          const x    = nodeX(i);
          const done = i < current;
          return (
            <g key={i}>
              {done ? (
                // Completed — filled blue + check
                <>
                  <circle cx={x} cy={cy} r={nodeR} fill="#4F58FF"/>
                  <path d={`M${x - 4.5},${cy + 0.5} l3.5 3.5 l6.5 -6.5`}
                    stroke="white" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"/>
                </>
              ) : i === current ? (
                // Active — filled Odyssey blue with white center dot
                <>
                  <circle cx={x} cy={cy} r={nodeR} fill="#4F58FF"/>
                  <circle cx={x} cy={cy} r="3.5" fill="white"/>
                </>
              ) : (
                // Future — empty circle
                <circle cx={x} cy={cy} r={nodeR} fill="white" stroke="#E8EAEF" strokeWidth="1.5"/>
              )}
            </g>
          );
        })}

        {/* ── Ship — slides along the track to current position ── */}
        {/* CSS transform transitions between steps; nested float keeps bobbing independent */}
        <g style={{
          transform: `translateX(${shipX}px)`,
          transition: "transform 0.55s cubic-bezier(0.4, 0, 0.2, 1)",
          transformOrigin: "0 0",
        }}>
          <g style={{ transformOrigin: "0 0" }} className="animate-boat-float">
            {/* Hull base sits on cy (the route line) */}
            {/* Wake — behind the boat */}
            <path d="M-16 2 C-21 1 -25 2.5 -28 2" stroke="#4F58FF"
              strokeWidth="0.9" strokeOpacity="0.28" fill="none" strokeLinecap="round"/>
            {/* Hull */}
            <path d={`M-10 0 L-11 ${cy * 0.22} L9 ${cy * 0.22} L10 0 Z`} fill="#4F58FF"/>
            {/* Mast — goes upward from the line */}
            <line x1="1" y1={-cy * 0.77} x2="1" y2="0" stroke="#3840E0" strokeWidth="1.1"/>
            {/* Main sail */}
            <path d={`M1 ${-cy * 0.75} L10 0 L1 0 Z`} fill="#4F58FF" fillOpacity="0.38"/>
            {/* Jib */}
            <path d={`M1 ${-cy * 0.6} L-8 0 L1 0 Z`} fill="#4F58FF" fillOpacity="0.20"/>
          </g>
        </g>
      </svg>

      {/* Step labels — below each node */}
      {steps.map((label, i) => {
        const x     = nodeX(i);
        const done  = i < current;
        const active = i === current;
        return (
          <span
            key={i}
            style={{
              position: "absolute",
              left: x,
              top: cy * 2 + 6,
              transform: "translateX(-50%)",
              whiteSpace: "nowrap",
            }}
            className={`text-[11px] font-semibold leading-none
              ${active ? "text-[#4F58FF]" : done ? "text-[#4F58FF]" : "text-[#9CA3AF]"}`}
          >
            {label}
          </span>
        );
      })}
    </nav>
  );
}

// ─── Goal Progress Bar ──────────────────────────────────────────
export function GoalProgressBar({ current, total, showLabels = true }: { current: number; total: number; showLabels?: boolean }) {
  const pct = Math.min(100, (current / total) * 100);
  return (
    <div className="flex flex-col gap-2.5">
      <div className="relative w-full bg-[#EEF0FF] rounded-full h-3 overflow-hidden">
        <div
          className="h-full rounded-full transition-all duration-700 ease-out"
          style={{ width: `${pct}%`, background: "#4F58FF" }}
        />
      </div>
      {showLabels && (
        <div className="flex items-center justify-between">
          <span className="num text-[12px] text-[#9CA3AF]">{current.toLocaleString("ko-KR")}원</span>
          <span className="num text-[12px] font-semibold text-[#4F58FF]">{pct.toFixed(1)}%</span>
          <span className="num text-[12px] text-[#9CA3AF]">{total.toLocaleString("ko-KR")}원</span>
        </div>
      )}
    </div>
  );
}

// ─── Section Card ───────────────────────────────────────────────
export function SectionCard({ title, desc, children, className = "", action }: {
  title?: string; desc?: string; children: React.ReactNode; className?: string; action?: React.ReactNode;
}) {
  return (
    <div className={`bg-white rounded-[20px] border border-[#E8EAEF] ${className}`}>
      {(title || action) && (
        <div className="px-6 pt-6 pb-0 flex items-start justify-between">
          <div>
            {title && <h3 className="text-[15px] font-bold text-[#111827]">{title}</h3>}
            {desc && <p className="text-[13px] text-[#6B7280] mt-0.5">{desc}</p>}
          </div>
          {action}
        </div>
      )}
      <div className={`p-6 ${title ? "pt-5" : ""}`}>{children}</div>
    </div>
  );
}

// ─── Metric Card ────────────────────────────────────────────────
export function MetricCard({ title, value, unit = "원", sub, variant = "default", trend, className = "" }: {
  title: string; value: string | number; unit?: string; sub?: React.ReactNode;
  variant?: "default" | "accent" | "success" | "warning"; trend?: React.ReactNode; className?: string;
}) {
  const styles = {
    default: "bg-white border border-[#E8EAEF]",
    accent:  "bg-[#4F58FF] border-[#3840E0]",
    success: "bg-[#EDFAF4] border-[#A3E8CC]",
    warning: "bg-[#FFFBEB] border-[#FDE68A]",
  };
  const textStyles = {
    default: { title: "text-[#6B7280]", value: "text-[#111827]", unit: "text-[#9CA3AF]", sub: "text-[#9CA3AF]" },
    accent:  { title: "text-[#A5ABFF]", value: "text-white",    unit: "text-[#A5ABFF]", sub: "text-[#A5ABFF]" },
    success: { title: "text-[#065F46]", value: "text-[#065F46]", unit: "text-[#065F46]", sub: "text-[#0DB97A]" },
    warning: { title: "text-[#92400E]", value: "text-[#78350F]", unit: "text-[#92400E]", sub: "text-[#B45309]" },
  };
  const t = textStyles[variant];
  return (
    <div className={`rounded-[20px] border p-6 ${styles[variant]} ${className}`}>
      <div className="flex items-start justify-between mb-3">
        <p className={`text-[13px] font-medium ${t.title}`}>{title}</p>
        {trend}
      </div>
      <p className={`num font-bold text-[28px] leading-none ${t.value}`}>
        {typeof value === "number" ? value.toLocaleString("ko-KR") : value}
        <span className={`ml-1 text-[14px] font-semibold ${t.unit}`}>{unit}</span>
      </p>
      {sub && <div className={`mt-2.5 text-[13px] ${t.sub}`}>{sub}</div>}
    </div>
  );
}

// ─── Alert Banner ───────────────────────────────────────────────
export function AlertBanner({ variant = "warning", icon, title, description, action, onAction, onDismiss }: {
  variant?: "warning" | "info" | "success" | "error";
  icon?: React.ReactNode; title: string; description?: string;
  action?: string; onAction?: () => void; onDismiss?: () => void;
}) {
  const styles = {
    warning: "bg-[#FFFBEB] border-[#FDE68A]",
    info:    "bg-[#EEF0FF] border-[#C7CAFF]",
    success: "bg-[#EDFAF4] border-[#A3E8CC]",
    error:   "bg-[#FFF5F5] border-[#FEB2B2]",
  };
  const textStyles = {
    warning: "text-[#78350F]",
    info:    "text-[#3840E0]",
    success: "text-[#065F46]",
    error:   "text-[#9B2C2C]",
  };
  return (
    <div className={`w-full border rounded-[16px] px-5 py-4 flex items-start gap-3.5 ${styles[variant]}`}>
      {icon && <span className="flex-shrink-0 mt-0.5">{icon}</span>}
      <div className="flex-1 min-w-0">
        <p className={`font-semibold text-[13px] ${textStyles[variant]}`}>{title}</p>
        {description && <p className={`text-[13px] mt-0.5 opacity-80 ${textStyles[variant]}`}>{description}</p>}
      </div>
      <div className="flex items-center gap-3 flex-shrink-0">
        {action && onAction && (
          <button onClick={onAction}
            className={`text-[13px] font-bold underline underline-offset-2 cursor-pointer hover:opacity-70 ${textStyles[variant]}`}>
            {action}
          </button>
        )}
        {onDismiss && (
          <button onClick={onDismiss} className={`opacity-50 hover:opacity-80 cursor-pointer ${textStyles[variant]}`}>
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M2.5 2.5l9 9M11.5 2.5l-9 9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/>
            </svg>
          </button>
        )}
      </div>
    </div>
  );
}

// ─── Modal ──────────────────────────────────────────────────────
export function Modal({ open, onClose, title, subtitle, children, size = "md" }: {
  open: boolean; onClose: () => void; title: string; subtitle?: string; children: React.ReactNode; size?: "sm" | "md" | "lg";
}) {
  if (!open) return null;
  const widths = { sm: "max-w-sm", md: "max-w-md", lg: "max-w-lg" };
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true">
      <div className="absolute inset-0 bg-[#111827]/25 backdrop-blur-[3px]" onClick={onClose}/>
      <div className={`relative bg-white rounded-[24px] shadow-2xl shadow-black/15 w-full ${widths[size]} p-8 z-10`}>
        <div className="mb-6">
          <h2 className="text-[19px] font-bold text-[#111827]">{title}</h2>
          {subtitle && <p className="text-[13px] text-[#6B7280] mt-1.5 leading-relaxed">{subtitle}</p>}
        </div>
        {children}
      </div>
    </div>
  );
}

// ─── Scheduled Expense Item ─────────────────────────────────────
export function ScheduledExpenseItem({ name, date, amount, onEdit, onDelete }: {
  name: string; date: string; amount: number; onEdit?: () => void; onDelete?: () => void;
}) {
  return (
    <div className="flex items-center gap-3.5 px-4 py-3.5 bg-[#F8F9FB] rounded-[12px] group">
      <div className="w-9 h-9 rounded-[10px] bg-[#EEF0FF] flex items-center justify-center flex-shrink-0">
        <IconCalendar size={15} color="#4F58FF"/>
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-[14px] font-semibold text-[#111827] truncate">{name}</p>
        <p className="text-[12px] text-[#9CA3AF] mt-0.5">{date}</p>
      </div>
      <span className="num text-[14px] font-bold text-[#111827] flex-shrink-0">{amount.toLocaleString("ko-KR")}원</span>
      {(onEdit || onDelete) && (
        <div className="flex gap-1">
          {onEdit && (
            <button aria-label={`${name} 수정`} onClick={onEdit} className="text-[12px] text-[#7C8594] hover:text-[#4F58FF] px-2 py-1 rounded-[8px] hover:bg-[#EEF0FF] cursor-pointer font-medium">수정</button>
          )}
          {onDelete && (
            <button aria-label={`${name} 삭제`} onClick={onDelete} className="text-[12px] text-[#7C8594] hover:text-[#E53E3E] px-2 py-1 rounded-[8px] hover:bg-[#FFF5F5] cursor-pointer font-medium">삭제</button>
          )}
        </div>
      )}
    </div>
  );
}

// ─── Category Spending Row ──────────────────────────────────────
export function CategorySpendingRow({ name, amount, max, color = "#4F58FF", emoji }: {
  name: string; amount: number; max: number; color?: string; emoji?: string;
}) {
  const pct = Math.min(100, (amount / max) * 100);
  return (
    <div className="flex items-center gap-4 py-0.5">
      {emoji && <span className="text-base w-6 flex-shrink-0 text-center">{emoji}</span>}
      <span className="w-14 text-[13px] font-medium text-[#4B5563] flex-shrink-0">{name}</span>
      <div className="flex-1 bg-[#F3F4F6] rounded-full h-2 overflow-hidden">
        <div className="h-full rounded-full transition-all duration-500" style={{ width: `${pct}%`, backgroundColor: color }}/>
      </div>
      <span className="num text-[13px] font-bold text-[#111827] w-28 text-right flex-shrink-0">{amount.toLocaleString("ko-KR")}원</span>
    </div>
  );
}

// ─── Info Tooltip ────────────────────────────────────────────────
function InfoTooltip({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <span
      className="relative inline-flex items-center"
      onClick={e => e.stopPropagation()}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <span className="w-[15px] h-[15px] rounded-full bg-[#E8EAEF] text-[#9CA3AF] text-[9px] font-bold flex items-center justify-center cursor-default select-none hover:bg-[#D1D5DB] transition-colors">
        i
      </span>
      {open && (
        <span
          className="absolute bottom-full left-1/2 z-50 w-60 bg-[#111827] text-white text-[11px] leading-[1.65] px-3 py-2.5 rounded-[10px] shadow-xl pointer-events-none"
          style={{ transform: "translateX(-50%) translateY(-6px)" }}
        >
          {text}
          <span
            className="absolute left-1/2 top-full border-[5px] border-transparent border-t-[#111827]"
            style={{ transform: "translateX(-50%)" }}
          />
        </span>
      )}
    </span>
  );
}

// ─── Plan Card ──────────────────────────────────────────────────
export function PlanCard({ label, title, description, amount, rate, warning, warningText, percentUnder, onSelect, selected, onDetailView, compact, hideAction }: {
  label: string; amount: number; rate: number; warning?: boolean;
  warningText?: string; percentUnder?: number; onSelect: () => void; selected?: boolean;
  onDetailView?: () => void; compact?: boolean; title?: string; description?: string; hideAction?: boolean;
}) {
  const rateColor = rate >= 80 ? "#0DB97A" : rate >= 70 ? "#E09B00" : "#E53E3E";
  return (
    <div
      onClick={onSelect}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onSelect();
        }
      }}
      role="button"
      tabIndex={0}
      aria-pressed={selected}
      className={`relative rounded-[20px] border-2 flex flex-col cursor-pointer transition-all duration-200 select-none overflow-hidden focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#4F58FF] focus-visible:ring-offset-2
        ${compact ? "p-5" : "p-7"}
        ${selected
          ? "border-[#4F58FF] bg-[#EEF0FF]"
          : "border-[#E8EAEF] bg-white hover:border-[#C7CAFF]"}`}
    >
      {/* Subtle wave decoration at card bottom */}
      <div className="absolute bottom-0 left-0 right-0 h-10 pointer-events-none overflow-hidden">
        <svg viewBox="0 0 300 20" preserveAspectRatio="none" style={{ width: "100%", height: "100%" }}>
          <path
            d="M0,10 C30,4 60,16 90,10 C120,4 150,16 180,10 C210,4 240,16 270,10 C285,7 295,12 300,10 L300,20 L0,20 Z"
            fill={selected ? "#4F58FF" : warning ? "#E09B00" : "#4F58FF"}
            fillOpacity={selected ? "0.07" : warning ? "0.05" : "0.04"}
          />
        </svg>
      </div>

      {title && (
        <div className="mb-5">
          <p className="text-[18px] font-bold tracking-tight text-[#111827]">{title}</p>
          {description && <p className="mt-1 text-[12px] leading-relaxed text-[#6B7280]">{description}</p>}
        </div>
      )}

      {/* Header row */}
      <div className="flex items-center justify-between mb-5">
        <span className={`text-[11px] font-bold tracking-widest uppercase ${selected ? "text-[#4F58FF]" : "text-[#9CA3AF]"}`}>
          {label}
        </span>
        <div className="flex items-center gap-2">
          {selected && (
            <div className="w-5 h-5 rounded-full bg-[#4F58FF] flex items-center justify-center">
              <IconCheck size={11} color="white"/>
            </div>
          )}
        </div>
      </div>

      {/* Amount — hero number */}
      <div className="mb-5">
        <p className="text-[12px] text-[#9CA3AF] mb-1">월 유동지출</p>
        <p className={`num font-bold leading-none ${compact ? "text-[30px]" : "text-[38px]"} ${selected ? "text-[#4F58FF]" : "text-[#111827]"}`}>
          {Math.round(amount / 10000).toLocaleString("ko-KR")}
          <span className={`${compact ? "text-[16px]" : "text-[18px]"} font-semibold text-[#9CA3AF] ml-1.5`}>만원</span>
        </p>
      </div>

      {/* Rate chip */}
      <div className={`flex items-center justify-between rounded-[12px] px-4 py-3 mb-3 ${selected ? "bg-white/70" : "bg-[#F8F9FB]"}`}>
        <span className="flex items-center gap-1.5 text-[12px] text-[#6B7280]">
          계획 안정성
          <InfoTooltip text="과거 소비 변동을 반영한 시뮬레이션에서 해당 계획의 소비 기준을 충족하는 정도를 나타냅니다."/>
        </span>
        <span className="num text-[20px] font-bold" style={{ color: rateColor }}>{rate}%</span>
      </div>

      {/* Progress bar */}
      <div className="w-full bg-[#E8EAEF] rounded-full h-1.5 mb-4 overflow-hidden">
        <div className="h-full rounded-full" style={{ width: `${rate}%`, backgroundColor: rateColor }}/>
      </div>

      {/* Extra info */}
      {percentUnder !== undefined && (
        <div className={`flex items-center justify-between rounded-[12px] px-4 py-2.5 mb-3 ${selected ? "bg-white/70" : "bg-[#F8F9FB]"}`}>
          <span className="text-[12px] text-[#6B7280]">이 수준 이하였던 달</span>
          <span className="num text-[13px] font-bold text-[#E53E3E]">24개월 중 {percentUnder}%</span>
        </div>
      )}

      {/* Warning */}
      {warning && warningText && (
        <div className="flex items-start gap-2.5 bg-[#FEF3C7] rounded-[10px] px-3.5 py-3 mb-3">
          <IconWarning size={14}/>
          <p className="text-[12px] text-[#92400E] leading-snug">{warningText}</p>
        </div>
      )}

      {onDetailView && (
        <button
          onClick={(e) => { e.stopPropagation(); onDetailView(); }}
          className="text-[12px] text-[#4F58FF] font-semibold underline underline-offset-2 hover:opacity-70 cursor-pointer mb-3 self-start"
        >
          최근 소비내역 상세보기 →
        </button>
      )}

      {!hideAction && (
        <Button
          variant={selected ? "primary" : "secondary"}
          size="sm"
          className="w-full mt-auto"
          onClick={(e) => { e.stopPropagation(); onSelect(); }}
        >
          {selected ? "선택됨" : "이 계획 선택"}
        </Button>
      )}
    </div>
  );
}

// ─── Error / Warning Notice Card ────────────────────────────────
export function NoticeCard({ type = "error", title, description, actions }: {
  type?: "error" | "warning" | "info";
  title: string; description?: string; actions?: React.ReactNode;
}) {
  const styles = {
    error:   { wrap: "bg-[#FFF5F5] border-[#FEB2B2]", icon: "#E53E3E", iconBg: "bg-[#FEE2E2]", title: "text-[#9B2C2C]", desc: "text-[#E53E3E]" },
    warning: { wrap: "bg-[#FFFBEB] border-[#FDE68A]", icon: "#E09B00", iconBg: "bg-[#FEF3C7]", title: "text-[#78350F]", desc: "text-[#B45309]" },
    info:    { wrap: "bg-[#EEF0FF] border-[#C7CAFF]", icon: "#4F58FF", iconBg: "bg-[#EEF0FF]",  title: "text-[#3840E0]", desc: "text-[#4F58FF]" },
  };
  const s = styles[type];
  return (
    <div className={`border rounded-[16px] p-5 ${s.wrap}`}>
      <div className="flex items-start gap-3">
        <div className={`w-8 h-8 rounded-full ${s.iconBg} flex items-center justify-center flex-shrink-0 mt-0.5`}>
          {type === "error" ? (
            <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
              <path d="M6.5 3v4M6.5 8.5v.5" stroke={s.icon} strokeWidth="1.8" strokeLinecap="round"/>
              <circle cx="6.5" cy="6.5" r="5.5" stroke={s.icon} strokeWidth="1.3"/>
            </svg>
          ) : type === "warning" ? <IconWarning size={14} color={s.icon}/> : (
            <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
              <circle cx="6.5" cy="6.5" r="5.5" stroke={s.icon} strokeWidth="1.3"/>
              <path d="M6.5 5.5v4M6.5 3.5v.5" stroke={s.icon} strokeWidth="1.8" strokeLinecap="round"/>
            </svg>
          )}
        </div>
        <div className="flex-1">
          <p className={`text-[13px] font-semibold ${s.title}`}>{title}</p>
          {description && <p className={`text-[13px] mt-1 leading-relaxed ${s.desc}`}>{description}</p>}
          {actions && <div className="mt-3.5 flex gap-2">{actions}</div>}
        </div>
      </div>
    </div>
  );
}

// ─── Header ─────────────────────────────────────────────────────
export function AppHeader({ goalName = "독립자금", onNavigate }: { goalName?: string; onNavigate?: (s: number) => void }) {
  return (
    <header className="sticky top-0 z-40 bg-white/95 backdrop-blur-md border-b border-[#E8EAEF]">
      <div className="max-w-[1280px] mx-auto px-8 h-[60px] flex items-center justify-between">
        {/* Logo */}
        <button onClick={() => onNavigate?.(8)} className="flex items-center gap-2.5 cursor-pointer group focus:outline-none">
          <div className="w-[32px] h-[32px] rounded-[10px] bg-[#4F58FF] flex items-center justify-center">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path d="M8 1.5L14 5.5v5L8 14.5 2 10.5v-5L8 1.5z" fill="white" fillOpacity="0.85"/>
              <circle cx="8" cy="8" r="2" fill="white"/>
            </svg>
          </div>
          <span className="font-bold text-[17px] text-[#111827] tracking-tight group-hover:text-[#4F58FF] transition-colors">Odyssey</span>
        </button>

        {/* Goal pill */}
        <div className="flex items-center gap-1.5 bg-[#F5F6F9] rounded-full px-4 py-2 border border-[#E8EAEF]">
          <svg width="13" height="13" viewBox="0 0 13 13" fill="none">
            <path d="M6.5 1L8 4.5l3.5.5-2.5 2.5.5 3.5L6.5 9 4 11l.5-3.5L2 5l3.5-.5L6.5 1z" fill="#4F58FF"/>
          </svg>
          <span className="text-[13px] font-semibold text-[#111827]">{goalName}</span>
        </div>

        {/* Right nav */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => onNavigate?.(9)}
            className="text-[13px] font-medium text-[#6B7280] hover:text-[#4F58FF] px-3 py-1.5 rounded-[8px] hover:bg-[#EEF0FF] transition-all cursor-pointer"
          >
            계획 설정
          </button>
          <div className="w-[34px] h-[34px] rounded-full bg-[#EEF0FF] flex items-center justify-center cursor-pointer hover:bg-[#DDE0FF] transition-colors border border-[#C7CAFF]">
            <span className="text-[13px] font-bold text-[#4F58FF]">김</span>
          </div>
        </div>
      </div>
    </header>
  );
}

// ─── Screen wrapper (for onboarding flow) ───────────────────────
export function OnboardingLayout({ children, maxWidth = 720 }: { children: React.ReactNode; maxWidth?: number }) {
  return (
    <div className="min-h-screen bg-[#F5F6F9]">
      <div className="mx-auto px-6 py-12" style={{ maxWidth }}>
        {children}
      </div>
    </div>
  );
}

// ─── Divider ────────────────────────────────────────────────────
export function Divider({ label }: { label?: string }) {
  if (!label) return <div className="border-t border-[#E8EAEF]"/>;
  return (
    <div className="flex items-center gap-3">
      <div className="flex-1 border-t border-[#E8EAEF]"/>
      <span className="text-[11px] font-semibold text-[#9CA3AF] uppercase tracking-wide">{label}</span>
      <div className="flex-1 border-t border-[#E8EAEF]"/>
    </div>
  );
}
