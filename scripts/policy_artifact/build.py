#!/usr/bin/env python3
"""공식 정책 페이지를 수집해 사람 승인 전 KURE 후보 artifact를 만든다."""

from __future__ import annotations

import argparse
import hashlib
import html
from html.parser import HTMLParser
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import re
import sys
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "data/policy"
RAW = OUT / "raw"
DEFAULT_MODEL_ROOT = Path.home() / ".cache/huggingface/hub/models--nlpai-lab--KURE-v1"
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
GOALS = {
    "PURCHASE": "청년 주택 구입과 내 집 마련 지원 정책",
    "JEONSE": "청년 전세 보증금 대출과 이자 지원 정책",
    "MONTHLY_RENT": "청년 월세와 주거급여 지원 정책",
    "PUBLIC_RENTAL": "청년 공공임대 행복주택 매입임대 전세임대 정책",
    "SUBSCRIPTION": "청년 주택 청약과 특별공급 정책",
    "MOVING_COST": "청년 이사비와 부동산 중개보수 지원 정책",
    "GUARANTEE": "청년 전세보증과 보증료 지원 정책",
    "DORMITORY": "청년 기숙사와 공동주거 지원 정책",
}

# 조사 27개 중 경기도의 중앙 월세사업 중복(#15)을 제외한다.
CANDIDATES = [
    ("kr-youth-rent-2026", "청년월세 지원사업", "MONTHLY_RENT", "국토교통부·복지로", "https://m.bokjiro.go.kr/ssis-tem/ssis-tem/twataa/wlfareInfo/moveTWAT52011M.do?wlfareInfoId=WLF00004661", "기준연도 2026", "wlfareInfoId=WLF00004661 서비스 내용 지원대상 신청방법", "ELIGIBILITY_ONLY"),
    ("kr-happy-housing", "행복주택 청년 계층", "PUBLIC_RENTAL", "국토교통부·LH 마이홈", "https://www.myhome.go.kr/hws/portal/cont/selectHappyHouseView.do", "2026년도 적용기준", "입주자격 청년 계층 소득기준", "ELIGIBILITY_ONLY"),
    ("kr-youth-jeonse-rental", "청년 기존주택 전세임대", "PUBLIC_RENTAL", "국토교통부·LH 마이홈", "https://www.myhome.go.kr/hws/portal/cont/selectCharterRentalHouseView.do", "2026-09-01 열람본", "청년 19~39세 순위 임대기간", "ELIGIBILITY_ONLY"),
    ("kr-youth-purchase-rental", "청년 매입임대주택", "PUBLIC_RENTAL", "국토교통부·LH 마이홈", "https://www.myhome.go.kr/hws/portal/cont/selectYouthPolicyPurchaseRentalView.do", "2026-09-01 열람본", "매입임대 입주자 모집공고", "ELIGIBILITY_ONLY"),
    ("kr-youth-housing-benefit-separate", "청년 주거급여 분리지급", "MONTHLY_RENT", "국토교통부·LH 마이홈", "https://www.myhome.go.kr/hws/portal/dgn/selectSelfDiagnosisYouthHousView.do", "2026-09-01 열람본", "청년주거급여 신청안내 분리지급액", "ELIGIBILITY_ONLY"),
    ("kr-hf-youth-jeonse-guarantee", "무주택 청년 특례전세자금보증", "GUARANTEE", "한국주택금융공사", "https://www.hf.go.kr/ko/sub02/sub02_01_04.do", "2026-09-01 열람본", "특례전세자금보증 무주택 청년", "ELIGIBILITY_ONLY"),
    ("kr-youth-beotimmok", "청년전용 버팀목전세자금", "JEONSE", "국토교통부·주택도시기금", "https://nhuf.molit.go.kr/cms/main/TypeB.jsp?cid=1605", "공식 변경 안내", "cid=1605 청년전용 버팀목전세자금 변경", "INFORMATIONAL"),
    ("kr-youth-deposit-monthly-loan", "청년전용 보증부월세대출", "JEONSE", "서울특별시 주거포털", "https://housing.seoul.go.kr/site/main/content/sh01_070400", "2026-09-01 열람본", "청년전용 보증부월세대출 주택도시기금 링크", "INFORMATIONAL"),
    ("seoul-youth-rent-2026", "2026년 서울시 청년월세지원", "MONTHLY_RENT", "서울특별시", "https://www.seoul.go.kr/news/news_notice.do?nttNo=457234&tr_code=snews", "서울특별시 공고 제2026-1440호", "공고 제2026-1440호 사업 개요 지원 내용 추진 일정", "ELIGIBILITY_ONLY"),
    ("seoul-moving-cost-2026", "2026년 청년 부동산 중개보수 및 이사비 지원", "MOVING_COST", "서울특별시", "https://soco.seoul.go.kr/youth/bbs/BMSR00013/view.do?boardId=6486&menuNo=400018&pageIndex=1", "서울특별시 공고 제2026-1109호", "첨부 모집 공고문 지원내용", "ELIGIBILITY_ONLY"),
    ("seoul-youth-deposit-interest-20260605", "청년 임차보증금 이자지원", "JEONSE", "서울특별시", "https://housing.seoul.go.kr/site/main/content/sh01_040901", "2026-06-05 시행 공고", "청년 임차보증금 이자지원사업 공고문", "ELIGIBILITY_ONLY"),
    ("seoul-youth-safe-housing-public-2026-2", "2026년 2차 청년안심주택 공공임대", "PUBLIC_RENTAL", "서울특별시·SH", "https://housing.seoul.go.kr/site/main/sh/publicLease/list?cp=1&splyCd=10&supplyType=publicLease", "2026-07-31 게시", "2026년 2차 청년안심주택 공공임대 입주자 모집공고", "ELIGIBILITY_ONLY"),
    ("seoul-youth-safe-housing-private", "청년안심주택 공공지원민간임대", "PUBLIC_RENTAL", "서울특별시", "https://housing.seoul.go.kr/site/main/content/sh01_060502", "2026-09-01 열람본", "공급 유형 민간임대 임대료 수준", "INFORMATIONAL"),
    ("seoul-one-two-person-urban-housing-20260805", "2026년 1~2인가구 도시형생활주택 잔여세대", "PUBLIC_RENTAL", "서울특별시·SH", "https://housing.seoul.go.kr/site/main/home?Sid=301_21", "2026-08-05 공고", "도시형생활주택 잔여세대", "INFORMATIONAL"),
    ("gg-gh-youth-purchase-rental-2026-1", "2026년 1차 GH 청년 매입임대주택", "PUBLIC_RENTAL", "경기주택도시공사", "https://apply.gh.or.kr/sb/sr/sr7155/selectPbancRentHouseList.do", "2026-04-17 게시", "26년 1차 청년 매입임대주택 예비입주자 모집공고", "ELIGIBILITY_ONLY"),
    ("gg-happy-housing-2026-h1-extra", "2026년 상반기 경기행복주택 예비입주자 추가모집", "PUBLIC_RENTAL", "경기주택도시공사", "https://apply.gh.or.kr/sb/sr/sr7150/selectPbancDetailView.do?pbancNo=788", "2026-05-04 최종파일", "pbancNo=788 정정공고 공급정보", "ELIGIBILITY_ONLY"),
    ("gg-suwon-youth-housing-package-2026", "수원시 청년 주거 패키지 지원", "MOVING_COST", "수원시·경기청년포털", "https://youth.gg.go.kr/gg/info/housing-welfare.do?arcNo=10046&mode=view", "2026-01 모집", "arcNo=10046 지원내용", "ELIGIBILITY_ONLY"),
    ("gg-hwaseong-youth-rent-2026", "화성시 청년 월세 지원", "MONTHLY_RENT", "화성시·경기청년포털", "https://youth.gg.go.kr/gg/info/housing-welfare.do?arcNo=10312&mode=view", "2026년 공고", "arcNo=10312 지원내용", "ELIGIBILITY_ONLY"),
    ("gg-anseong-moving-cost-2026", "안성시 청년 부동산 중개수수료 및 이사비 지원", "MOVING_COST", "안성시·경기청년포털", "https://youth.gg.go.kr/gg/info/housing-welfare.do?arcNo=10327&mode=view", "2026년 공고", "arcNo=10327 사업대상 지원내용", "ELIGIBILITY_ONLY"),
    ("gg-hwaseong-deposit-interest-2026-h2", "화성시 청년 전월세 보증금 대출이자 지원", "JEONSE", "화성시·경기청년포털", "https://youth.gg.go.kr/gg/info/housing-welfare.do?arcNo=11684&mode=view", "2026년 하반기 공고", "arcNo=11684 지원대상 지원내용", "ELIGIBILITY_ONLY"),
    ("gg-pocheon-deposit-interest-2026", "포천시 청년가구 전월세 보증금 대출이자 지원", "JEONSE", "포천시·경기청년포털", "https://youth.gg.go.kr/gg/info/housing-welfare.do?arcNo=10599&mode=view", "2026년 지원계획", "arcNo=10599 청년가구 기준 지원내용", "ELIGIBILITY_ONLY"),
    ("incheon-youth-rent-35-39-2026", "인천형 청년월세 지원사업 35~39세", "MONTHLY_RENT", "인천광역시", "https://www.incheon.go.kr/jobs/main/support/welfare/view.do?wl_srvc_sn=186", "2026년 대상 기간", "wl_srvc_sn=186 신청기간 선정기준", "ELIGIBILITY_ONLY"),
    ("incheon-youth-deposit-interest-2026", "인천시 청년 주택임차보증금 이자 지원", "JEONSE", "인천광역시", "https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoList.do?menudiv=dwelling&pgno=2", "2026년 공고", "청년 주택임차보증금 이자 지원 사업", "INFORMATIONAL"),
    ("incheon-ih-youth-purchase-rental-2026", "2026년 iH 기존주택 매입임대 청년형", "PUBLIC_RENTAL", "인천도시공사", "https://www.ih.co.kr/main/bbs/bbsMsgDetail.do?bcd=notice&msg_seq=4283&pgdiv=general&pgno=141", "2026-04-30 게시", "msg_seq=4283 첨부 공고문", "ELIGIBILITY_ONLY"),
    ("incheon-lh-youth-purchase-rental-2026-3", "LH 인천지역 2026년 3차 청년 매입임대", "PUBLIC_RENTAL", "LH 마이홈", "https://m.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20386", "2026-05-21 모집공고", "pblancId=20386 공고문", "ELIGIBILITY_ONLY"),
    ("incheon-jemulpo-welcome-moving-2026", "제물포구 청년 웰컴페이 이사비", "MOVING_COST", "제물포구·인천청년포털", "https://youth.incheon.go.kr/youthpolicy/youthPolicyInfoCalendar.do?policy_ym=2026-04", "2026년 사업", "제물포구 2026년 청년 웰컴페이 이사비 지원사업", "ELIGIBILITY_ONLY"),
    ("kr-youth-dream-didimdol", "청년 주택드림 디딤돌 대출", "PURCHASE", "국토교통부·주택도시기금", "https://nhuf.molit.go.kr/FP/FP05/FP0503/FP05030901.jsp", "2026-09-01 열람본", "청년 주택드림 디딤돌 대출 대출대상 대상주택", "ELIGIBILITY_ONLY"),
    ("kr-youth-dream-subscription", "청년 주택드림 청약통장", "SUBSCRIPTION", "국토교통부·주택도시기금", "https://nhuf.molit.go.kr/FP/FP07/FP0701/FP07010301.jsp", "2026-09-01 열람본", "청년 주택드림 청약통장 상품 가입대상", "ELIGIBILITY_ONLY"),
    ("kr-happy-dormitory", "행복기숙사 지원사업", "DORMITORY", "한국사학진흥재단", "https://www.kasfo.or.kr/kasfo/944/subview.do", "2026-09-01 열람본", "행복기숙사 지원사업 추진 및 지원근거", "INFORMATIONAL"),
]

MARKERS = {
    "kr-youth-rent-2026": ["청년월세 지원사업", "월세를 지원"],
    "kr-happy-housing": ["행복주택", "청년 계층"],
    "kr-youth-jeonse-rental": ["전세임대", "청년"],
    "kr-youth-purchase-rental": ["청년 매입임대"],
    "kr-youth-housing-benefit-separate": ["청년주거급여", "분리지급"],
    "kr-hf-youth-jeonse-guarantee": ["특례전세자금보증", "무주택 청년"],
    "kr-youth-beotimmok": ["청년전용 버팀목전세자금"],
    "kr-youth-deposit-monthly-loan": ["청년전용 보증부월세대출"],
    "seoul-youth-rent-2026": ["2026년 서울시 청년월세지원"],
    "seoul-moving-cost-2026": ["2026년 청년 부동산 중개보수 및 이사비 지원"],
    "seoul-youth-deposit-interest-20260605": ["청년 임차보증금 이자지원사업"],
    "seoul-youth-safe-housing-public-2026-2": ["2026년 2차 청년안심주택", "입주자 모집공고"],
    "seoul-youth-safe-housing-private": ["청년안심주택", "공공지원민간임대"],
    "seoul-one-two-person-urban-housing-20260805": ["1~2인가구", "잔여세대"],
    "gg-gh-youth-purchase-rental-2026-1": ["26년 1차 청년 매입임대주택", "예비입주자"],
    "gg-happy-housing-2026-h1-extra": ["경기행복주택", "예비입주자 추가모집"],
    "gg-suwon-youth-housing-package-2026": ["수원시 청년 주거 패키지"],
    "gg-hwaseong-youth-rent-2026": ["화성시 청년 월세 지원"],
    "gg-anseong-moving-cost-2026": ["안성시 청년 부동산 중개수수료 및 이사비 지원"],
    "gg-hwaseong-deposit-interest-2026-h2": ["화성시 청년 전(월)세 보증금 대출이자", "지원내용"],
    "gg-pocheon-deposit-interest-2026": ["포천시", "청년가구", "대출이자 지원"],
    "incheon-youth-rent-35-39-2026": ["인천형 청년월세 지원사업", "선정기준"],
    "incheon-youth-deposit-interest-2026": ["청년 주택임차보증금 이자 지원 사업"],
    "incheon-ih-youth-purchase-rental-2026": ["2026년 iH 기존주택 매입임대(청년형)", "예비입주자 모집"],
    "incheon-lh-youth-purchase-rental-2026-3": ["26-3차청년매입임대"],
    "incheon-jemulpo-welcome-moving-2026": ["청년 웰컴페이"],
    "kr-youth-dream-didimdol": ["청년 주택드림 디딤돌 대출", "대출대상"],
    "kr-youth-dream-subscription": ["청년 주택드림 청약통장", "가입대상"],
    "kr-happy-dormitory": ["행복기숙사 지원사업", "대학생 주거비 부담 경감"],
}


class TextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts: list[str] = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "noscript"}:
            self.hidden += 1

    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript"} and self.hidden:
            self.hidden -= 1

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def normalized(text):
    return re.sub(r"\s+", "", text).lower()


def toolchain():
    import numpy
    import torch

    numpy_config = numpy.show_config(mode="dicts")
    blas = numpy_config["Build Dependencies"]["blas"]
    return {
        "python": platform.python_version(),
        "sentenceTransformers": importlib.metadata.version("sentence-transformers"),
        "torch": importlib.metadata.version("torch"),
        "numpy": importlib.metadata.version("numpy"),
        "transformers": importlib.metadata.version("transformers"),
        "tokenizers": importlib.metadata.version("tokenizers"),
        "safetensors": importlib.metadata.version("safetensors"),
        "scipy": importlib.metadata.version("scipy"),
        "backend": "cpu",
        "machine": platform.machine(),
        "system": platform.system(),
        "byteorder": sys.byteorder,
        "blas": f"{blas['name']}@{blas['version']}",
        "torchBuildSha256": hashlib.sha256(torch.__config__.show().encode()).hexdigest(),
    }


def visible_text(raw: bytes) -> str:
    parser = TextExtractor()
    decoded = raw.decode("utf-8", errors="replace")
    parser.feed(decoded)
    text = re.sub(r"\s+", " ", html.unescape(" ".join(parser.parts))).strip()
    if len(text) < 80:
        embedded = re.search(r'"dmWlfareInfo":"((?:\\.|[^"\\])*)"', decoded)
        if embedded:
            data = json.loads(json.loads(f'"{embedded.group(1)}"'))
            fields = ("wlfareInfoNm", "wlfareInfoOutlCn", "wlfareSprtTrgtCn", "aplyMtdDc",
                      "wlfareSprtTrgtSlcrCn", "wlfareSprtBnftCn", "bizChrInstNm", "crtrYr")
            text = re.sub(r"<[^>]+>", " ", " ".join(str(data.get(field, "")) for field in fields))
            text = re.sub(r"\s+", " ", html.unescape(text)).strip()
    return text


def embed(texts, model_path):
    import numpy
    import torch
    from sentence_transformers import SentenceTransformer

    numpy.random.seed(0)
    torch.manual_seed(0)
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)
    model = SentenceTransformer(str(model_path), device="cpu", local_files_only=True)
    model.eval()
    model.max_seq_length = 512
    vectors = model.encode(
        texts, batch_size=2, normalize_embeddings=True, show_progress_bar=True
    )
    # BLAS별 마지막 비트 차이를 artifact identity에서 제거한다.
    result = [[round(float(x), 7) for x in row] for row in vectors]
    if any(len(row) != 1024 or not all(math.isfinite(x) for x in row) for row in result):
        raise ValueError("KURE-v1 output must be 1024 finite numbers")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--retrieved-at", required=True, help="고정 UTC ISO-8601 시각")
    parser.add_argument("--reuse-raw", action="store_true")
    parser.add_argument("--model-path", type=Path,
                        help="KURE snapshot 경로 (기본: ~/.cache/huggingface/.../refs/main)")
    args = parser.parse_args()
    model_root = DEFAULT_MODEL_ROOT
    model_revision = (model_root / "refs/main").read_text().strip()
    model_path = args.model_path or model_root / "snapshots" / model_revision
    model_id = f"nlpai-lab/KURE-v1@{model_path.name}"
    RAW.mkdir(parents=True, exist_ok=True)
    successes, failures, fetches = [], [], []
    for row in CANDIDATES:
        key, _, _, _, url, *_ = row
        path = RAW / f"{key}.html"
        try:
            previous = path.read_bytes() if path.exists() else None
            if not args.reuse_raw:
                request = urllib.request.Request(url, headers={"User-Agent": "DaconPolicyArtifact/1.0"})
                with urllib.request.urlopen(request, timeout=30) as response:
                    status = response.status
                    content_type = response.headers.get_content_type()
                    final_url = response.url
                    raw = response.read()
                path.write_bytes(raw)
            else:
                raw = path.read_bytes()
                status = 200
                content_type = "text/html"
                final_url = url
            text = visible_text(raw)
            marker_results = {marker: normalized(marker) in normalized(text)
                              for marker in MARKERS[key]}
            previous_text = visible_text(previous) if previous else None
            change = ("NEW_SOURCE" if previous is None else
                      "UNCHANGED_BYTES" if previous == raw else
                      "DYNAMIC_MARKUP_ONLY" if normalized(previous_text) == normalized(text) else
                      "SUBSTANTIVE_TEXT_CHANGE")
            evidence = {"policyKey": key, "requestedUrl": url, "finalUrl": final_url,
                        "httpStatus": status, "contentType": content_type,
                        "rawSha256": hashlib.sha256(raw).hexdigest(),
                        "previousRawSha256": hashlib.sha256(previous).hexdigest() if previous else None,
                        "textSha256": hashlib.sha256(normalized(text).encode()).hexdigest(),
                        "visibleTextLength": len(text), "markers": marker_results,
                        "changeClassification": change}
            fetches.append(evidence)
            if status != 200:
                raise ValueError(f"unexpected HTTP status: {status}")
            if content_type not in {"text/html", "application/xhtml+xml"}:
                raise ValueError(f"unexpected content type: {content_type}")
            if urlparse(final_url).hostname != urlparse(url).hostname:
                raise ValueError(f"final URL host changed: {final_url}")
            if len(text) < 300 or not all(marker_results.values()):
                missing = [marker for marker, found in marker_results.items() if not found]
                raise ValueError(f"non-substantive policy body; missing markers={missing}")
            successes.append((row, raw, text, evidence))
        except Exception as exc:
            failures.append({"policyKey": key, "url": url, "error": str(exc)})

    chunk_texts = [text[:5000] for _, _, text, _ in successes]
    all_vectors = embed(chunk_texts + list(GOALS.values()), model_path)
    sources, policies = [], []
    for index, ((row, raw, text, evidence), vector) in enumerate(zip(successes, all_vectors)):
        key, title, goal, org, url, version, locator, mode = row
        verified_locator = MARKERS[key][0]
        digest = hashlib.sha256(raw).hexdigest()
        sources.append({"sourceKey": key, "organization": org, "officialUrl": url,
                        "contentSha256": digest, "retrievedAt": args.retrieved_at,
                        "finalUrl": evidence["finalUrl"], "httpStatus": evidence["httpStatus"],
                        "contentType": evidence["contentType"], "bodyMarkers": MARKERS[key],
                        "textSha256": evidence["textSha256"]})
        content = text[:5000]
        policies.append({
            "policyKey": key, "title": title, "supportGoal": goal,
            "summary": content[:700],
            "planConnection": f"{GOALS[goal]} 탐색 후보이며 기관 확인 전 계산하지 않는다.",
            "version": {"sourceVersion": version, "reviewStatus": "PENDING",
                        "calculationMode": mode, "effectiveFrom": None, "effectiveTo": None,
                        "lastVerifiedAt": args.retrieved_at, "sourceKey": key,
                        "sourceLocator": verified_locator,
                        "locatorSha256": hashlib.sha256(verified_locator.encode()).hexdigest(),
                        "chunks": [{"chunkIndex": 0, "content": content, "embedding": vector,
                                    "metadata": {"officialUrl": url, "rawSha256": digest,
                                                 "rawPath": f"raw/{key}.html",
                                                 "sourceLocator": verified_locator}}],
                        "calculationRule": None}
        })
    profiles = []
    offset = len(successes)
    for index, (goal, query) in enumerate(GOALS.items()):
        profiles.append({"supportGoal": goal, "queryText": query,
                         "embedding": all_vectors[offset + index], "questionFlow": []})
    body = {"artifactVersion": f"candidate-2026-09-01-{model_path.name[:12]}",
            "manifestSha256": "", "embeddingModel": model_id, "embeddingDimension": 1024,
            "toolchain": toolchain(),
            "reviewGate": {"status": "PENDING", "importable": False,
                           "reason": "Core importer requires separately human-approved APPROVED versions."},
            "sources": sources, "policies": policies, "queryProfiles": profiles}
    body["manifestSha256"] = hashlib.sha256(canonical({**body, "manifestSha256": ""})).hexdigest()
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "policy-artifact-candidate.json").write_bytes(canonical(body) + b"\n")
    report = {"successCount": len(successes), "failureCount": len(failures), "failures": failures,
              "fetches": fetches, "toolchain": body["toolchain"],
              "modelSnapshot": model_path.name,
              "manifestSha256": body["manifestSha256"]}
    if not args.reuse_raw:
        (OUT / "fetch-report.json").write_bytes(canonical(report) + b"\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not 20 <= len(successes) <= 30:
        raise SystemExit("official fetch success count must be 20..30")


if __name__ == "__main__":
    main()
