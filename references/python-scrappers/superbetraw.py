#!/usr/bin/env python3
"""Superbet scraper v2: salva o payload bruto em events_superbet."""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Sequence

import requests
from pymongo import MongoClient, ReplaceOne

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.append(str(PROJECT_ROOT))

from superbet import superbet_scraper as superbet_mod

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

DEFAULT_MONGO_URI = (
    "mongodb://flashscore:flashscore@31.220.90.232:27017/"
    "?authSource=admin&connectTimeoutMS=5000&serverSelectionTimeoutMS=5000"
)
DEFAULT_MONGO_DB = "flashscore"
DEFAULT_MONGO_COLLECTION = "events_superbet"
DEFAULT_MARKET_IDS = [
    547,  # Resultado Final (1X2)
    539,  # Ambas as Equipes Marcam
    531,  # Dupla Chance
    555,  # Empate Anula Aposta
    546,  # Handicap 3-way
    530,  # Handicap Asiático
    532,  # Resultado Final & Ambas Marcam
    542,  # Dupla Chance & Total de Gols
    557,  # Resultado Final & Total de Gols
]
EVENT_DETAILS_PARAMS = {"includeMarkets": ",".join(str(market_id) for market_id in DEFAULT_MARKET_IDS)}
ALLOWED_MARKET_IDS: list[int] | None = DEFAULT_MARKET_IDS
DEFAULT_MAX_WORKERS = 8
MAX_WORKERS_CAP = 12
DEFAULT_FLUSH_BATCH = 200
_THREAD_LOCAL = threading.local()


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Coleta completa da Superbet para análise")
    parser.add_argument("--days", type=int, default=3, help="Dias (a partir de hoje) que serão coletados")
    parser.add_argument("--mongo-uri", default=DEFAULT_MONGO_URI, help="URI do MongoDB de destino")
    parser.add_argument("--mongo-db", default=DEFAULT_MONGO_DB, help="Banco no MongoDB")
    parser.add_argument(
        "--mongo-collection",
        default=DEFAULT_MONGO_COLLECTION,
        help="Coleção onde o dump bruto será salvo",
    )
    parser.add_argument("--json", type=Path, help="Arquivo opcional para salvar todos os eventos coletados")
    parser.add_argument(
        "--allow-past",
        action="store_true",
        help="Não filtra eventos já iniciados (por padrão apenas futuros são mantidos)",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=DEFAULT_MAX_WORKERS,
        help="Quantidade de workers paralelos para detalhar eventos (>=1)",
    )
    parser.add_argument(
        "--flush-size",
        type=int,
        default=DEFAULT_FLUSH_BATCH,
        help="Quantidade de eventos enriquecidos antes de salvar no Mongo (>=1)",
    )
    parser.add_argument(
        "--include-markets",
        help=(
            "Lista de IDs de mercado separados por v�rgula a ser enviada em includeMarkets "
            "(use 'all' para manter o comportamento antigo)."
        ),
    )
    return parser.parse_args(argv)


def build_event_params(include_markets_arg: str | None) -> tuple[dict[str, str], list[int] | None]:
    """Retorna o dicion�rio de query params para detalhar eventos e a lista de IDs para poda local."""
    if include_markets_arg:
        value = include_markets_arg.strip()
        if value:
            if value.lower() == "all":
                return {"includeMarkets": "all"}, None
            tokens = [token.strip() for token in re.split(r"[;,]", value) if token.strip()]
            if tokens:
                return {"includeMarkets": ",".join(tokens)}, [int(tok) for tok in tokens if tok.isdigit()]
    return {"includeMarkets": ",".join(str(market_id) for market_id in DEFAULT_MARKET_IDS)}, DEFAULT_MARKET_IDS


def prune_event_raw(raw_event: dict, allowed_markets: list[int]) -> dict:
    """Remove mercados desnecessários e campos pesados para reduzir tamanho em disco/memória."""
    # Remove bloco markets duplicado
    raw_event = dict(raw_event)
    raw_event.pop("markets", None)
    odds = raw_event.get("odds") or []
    filtered_odds = []
    for odd in odds:
        if odd.get("marketId") not in allowed_markets:
            continue
        slim = {
            "marketId": odd.get("marketId"),
            "marketName": odd.get("marketName"),
            "outcomeId": odd.get("outcomeId"),
            "name": odd.get("name"),
            "code": odd.get("code"),
            "price": odd.get("price"),
            "status": odd.get("status"),
        }
        # Mantém offerStateId e marketGroupOrder para ordenação/estado.
        if "offerStateId" in odd:
            slim["offerStateId"] = odd.get("offerStateId")
        if "marketGroupOrder" in odd:
            slim["marketGroupOrder"] = odd.get("marketGroupOrder")
        filtered_odds.append(slim)
    raw_event["odds"] = filtered_odds
    return raw_event


def save_raw_events(
    events: list[dict],
    args: argparse.Namespace,
    client: MongoClient | None = None,
    collection=None,
) -> None:
    if not events:
        logging.warning("Nenhum evento bruto para salvar")
        return
    close_client = False
    if collection is None:
        if client is None:
            client = MongoClient(args.mongo_uri)
            close_client = True
        collection = client[args.mongo_db][args.mongo_collection]
    operations: list[ReplaceOne] = []
    captured_at = datetime.utcnow()
    for event in events:
        raw_event = dict(event.get("raw") or event)
        event_id = raw_event.get("eventId") or event.get("event_id")
        if not event_id:
            continue
        doc = {
            "eventId": str(event_id),
            "matchName": raw_event.get("matchName") or raw_event.get("name"),
            "sportId": raw_event.get("sportId") or event.get("sport_id"),
            "tournamentId": raw_event.get("tournamentId") or event.get("tournament_id"),
            "capturedAt": captured_at,
            "source": "superbet",
            "raw": raw_event,
        }
        operations.append(ReplaceOne({"eventId": doc["eventId"]}, doc, upsert=True))
    if operations:
        result = collection.bulk_write(operations, ordered=False)
        logging.info(
            "Dump salvo em %s.%s (upserted=%s, modified=%s)",
            args.mongo_db,
            args.mongo_collection,
            result.upserted_count,
            result.modified_count,
        )
    if close_client and client:
        client.close()


def _thread_session() -> requests.Session:
    session = getattr(_THREAD_LOCAL, "session", None)
    if session is None:
        session = requests.Session()
        session.headers.update(superbet_mod.HEADERS)
        _THREAD_LOCAL.session = session
    return session


def fetch_full_event(session: requests.Session | None, event_id: str, retries: int = 3) -> dict | None:
    """Busca o payload completo (todos os mercados) de um evento específico."""
    url = f"{superbet_mod.BASE_URL}/v2/pt-BR/events/{event_id}"
    for attempt in range(1, retries + 1):
        try:
            request_session = session or _thread_session()
            resp = request_session.get(url, params=EVENT_DETAILS_PARAMS, timeout=20)
            if resp.status_code == 200:
                payload = resp.json()
                data = payload.get("data")
                if data:
                    raw_event = data[0]
                    if ALLOWED_MARKET_IDS:
                        raw_event = prune_event_raw(raw_event, ALLOWED_MARKET_IDS)
                    return raw_event
                logging.warning("Evento %s sem campo 'data' no detalhe", event_id)
            else:
                logging.warning("Falha %s ao detalhar evento %s", resp.status_code, event_id)
        except requests.RequestException as exc:
            logging.warning("Erro ao detalhar evento %s (tentativa %s/%s): %s", event_id, attempt, retries, exc)
        if attempt < retries:
            time.sleep(0.5 * attempt)
    return None


def enrich_events_with_markets(
    session: requests.Session, events: list[dict], max_workers: int = DEFAULT_MAX_WORKERS
):
    """Gera eventos enriquecidos com todos os mercados à medida em que cada detalhamento termina."""
    total = len(events)
    logging.info("Enriquecendo %s eventos com todos os mercados", total)
    if max_workers < 1:
        max_workers = 1
    max_workers = min(max_workers, MAX_WORKERS_CAP)

    if max_workers == 1:
        for idx, event in enumerate(events, 1):
            event_id = event.get("event_id")
            if event_id:
                full_payload = fetch_full_event(session, event_id)
                if full_payload:
                    event["raw"] = full_payload
                else:
                    logging.warning("Mantendo payload parcial para evento %s", event_id)
            else:
                logging.warning("Evento sem event_id na posição %s será mantido sem enriquecimento", idx)
            if idx % 100 == 0 or idx == total:
                logging.info("... %s/%s eventos enriquecidos", idx, total)
            yield event
        return

    with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="superbet") as executor:
        futures: dict = {}
        for idx, event in enumerate(events, 1):
            event_id = event.get("event_id")
            if not event_id:
                logging.warning("Evento sem event_id na posição %s será mantido sem enriquecimento", idx)
                yield event
                continue
            future = executor.submit(fetch_full_event, None, event_id)
            futures[future] = event

        completed = 0
        total_with_id = len(futures)
        for future in as_completed(futures):
            event = futures[future]
            try:
                full_payload = future.result()
            except Exception as exc:
                logging.warning("Erro ao detalhar evento %s: %s", event.get("event_id"), exc)
                full_payload = None
            if full_payload:
                event["raw"] = full_payload
            else:
                logging.warning("Mantendo payload parcial para evento %s", event.get("event_id"))
            completed += 1
            if completed % 100 == 0 or completed == total_with_id:
                logging.info("... %s/%s eventos enriquecidos em paralelo", completed, total_with_id)
            yield event


def parse_kickoff(value: str | None) -> datetime | None:
    if not value:
        return None
    value = value.strip()
    if not value:
        return None
    iso_value = value
    if iso_value.endswith("Z"):
        iso_value = iso_value[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(iso_value)
    except ValueError:
        try:
            return datetime.strptime(value, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        except ValueError:
            return None


def filter_future_events(events: list[dict], allow_past: bool) -> list[dict]:
    if allow_past:
        return events
    lower_bound = datetime.now(timezone.utc)
    upper_bound = lower_bound + timedelta(days=3)
    filtered: list[dict] = []
    skipped_past = skipped_future = skipped_missing = 0
    for event in events:
        kickoff = (
            event.get("kickoffRaw")
            or event.get("match_date")
            or event.get("matchDate")
            or (event.get("raw") or {}).get("utcDate")
        )
        kickoff_dt = parse_kickoff(kickoff)
        if not kickoff_dt:
            skipped_missing += 1
        elif kickoff_dt < lower_bound:
            skipped_past += 1
        elif kickoff_dt > upper_bound:
            skipped_future += 1
        else:
            filtered.append(event)
    skipped_total = skipped_past + skipped_future + skipped_missing
    if skipped_total:
        logging.info(
            "Eventos descartados: total=%s (passados=%s, acima_7d=%s, sem_data=%s)",
            skipped_total,
            skipped_past,
            skipped_future,
            skipped_missing,
        )
    return filtered


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    global EVENT_DETAILS_PARAMS, ALLOWED_MARKET_IDS
    EVENT_DETAILS_PARAMS, ALLOWED_MARKET_IDS = build_event_params(args.include_markets)
    session = requests.Session()
    session.headers.update(superbet_mod.HEADERS)
    logging.info(
        "Coletando Superbet v2 | dias=%s | includeMarkets=%s",
        args.days,
        EVENT_DETAILS_PARAMS.get("includeMarkets"),
    )
    events = superbet_mod.fetch_events(session, args.days)
    logging.info("Eventos coletados: %s", len(events))
    events = filter_future_events(events, args.allow_past)
    logging.info("Eventos após filtro temporal: %s", len(events))

    mongo_client = MongoClient(args.mongo_uri)
    collection = mongo_client[args.mongo_db][args.mongo_collection]
    buffer: list[dict] = []
    all_events: list[dict] = []
    flushed = 0
    for event in enrich_events_with_markets(session, events, args.max_workers):
        all_events.append(event)
        buffer.append(event)
        if len(buffer) >= max(1, args.flush_size):
            save_raw_events(buffer, args, client=mongo_client, collection=collection)
            flushed += len(buffer)
            buffer.clear()
    if buffer:
        save_raw_events(buffer, args, client=mongo_client, collection=collection)
        flushed += len(buffer)
    mongo_client.close()
    logging.info("Eventos persistidos no Mongo: %s", flushed)

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(
            json.dumps(
                {"scraped_at": datetime.utcnow().isoformat(), "events": all_events},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        logging.info("Dump salvo em %s", args.json)
    logging.info("Concluído")


if __name__ == "__main__":
    main()
