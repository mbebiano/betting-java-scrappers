#!/usr/bin/env python3
"""
Utilidades para montar e persistir documentos normalizados (eventos).

Responsabilidades:
- Dividir nomes de partidas em mandante/visitante.
- Calcular horário local a partir do UTC (America/Sao_Paulo).
- Mesclar documentos normalizados (mantendo uma entrada por provider).
- Upsert em coleção Mongo.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from pymongo import MongoClient, ReplaceOne
from zoneinfo import ZoneInfo

BR_TZ = ZoneInfo("America/Sao_Paulo")
UTC = timezone.utc


def split_match_name(name: str) -> Tuple[str, str]:
    text = str(name or "").strip()
    separators = [" - ", "-", " vs ", " v ", " x ", " X ", "·", "•", " – ", "–", "|"]
    for sep in separators:
        if sep in text:
            parts = [part.strip() for part in text.split(sep, 1)]
            if len(parts) == 2 and all(parts):
                return parts[0], parts[1]
    return text, ""


def to_utc_iso(value: Any) -> Optional[str]:
    if value is None:
        return None
    try:
        if isinstance(value, datetime):
            dt_value = value
        else:
            text = str(value).strip()
            if not text:
                return None
            if " " in text and "T" not in text:
                text = text.replace(" ", "T", 1)
            dt_value = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if dt_value.tzinfo is None:
            dt_value = dt_value.replace(tzinfo=UTC)
        return dt_value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    except Exception:
        return None


def to_local_iso(utc_iso: Optional[str]) -> Optional[str]:
    if not utc_iso:
        return None
    try:
        dt_value = datetime.fromisoformat(utc_iso.replace("Z", "+00:00"))
        return dt_value.astimezone(BR_TZ).isoformat()
    except Exception:
        return None


def merge_normalized(existing: Optional[Dict[str, Any]], incoming: Dict[str, Any]) -> Dict[str, Any]:
    merged = dict(existing) if existing else {}
    merged.setdefault("eventId", incoming.get("eventId"))
    merged.setdefault("normalizedId", incoming.get("normalizedId"))
    for field in ("home", "away", "kickoff"):
        if incoming.get(field):
            merged[field] = merged.get(field) or incoming[field]
    merged_sources = {src.get("provider"): src for src in merged.get("sources", []) if src.get("provider")}
    for src in incoming.get("sources", []):
        key = src.get("provider")
        if not key:
            continue
        merged_sources[key] = src
    merged["sources"] = list(merged_sources.values())
    merged["updatedAt"] = incoming.get("updatedAt") or datetime.now(tz=UTC)
    merged.setdefault("createdAt", incoming.get("createdAt") or datetime.now(tz=UTC))
    return merged


def _fetch_existing_map(collection, ids: List[str]) -> dict:
    existing: dict[str, Dict[str, Any]] = {}
    if not ids:
        return existing
    cursor = collection.find({"normalizedId": {"$in": ids}})
    for doc in cursor:
        existing[str(doc.get("normalizedId"))] = doc
    return existing


def upsert_normalized(
    documents: List[Dict[str, Any]],
    *,
    mongo_uri: str,
    mongo_db: str,
    mongo_collection: str,
) -> int:
    if not documents:
        return 0
    client = MongoClient(mongo_uri)
    collection = client[mongo_db][mongo_collection]
    norm_ids = [doc.get("normalizedId") or doc.get("eventId") for doc in documents if doc.get("normalizedId") or doc.get("eventId")]
    existing_map = _fetch_existing_map(collection, norm_ids)
    operations: List[ReplaceOne] = []
    for doc, norm_id in zip(documents, norm_ids):
        if not norm_id:
            continue
        existing = existing_map.get(str(norm_id))
        merged = merge_normalized(existing, doc)
        operations.append(ReplaceOne({"normalizedId": norm_id}, merged, upsert=True))
    upserted = 0
    if operations:
        result = collection.bulk_write(operations, ordered=False)
        upserted = (result.upserted_count or 0) + (result.modified_count or 0)
    client.close()
    return upserted
