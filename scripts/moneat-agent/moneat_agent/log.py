"""Structured, model-labeled console output."""

from __future__ import annotations

import sys
from datetime import datetime
from enum import Enum


class Color(Enum):
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    CYAN = "\033[36m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    RED = "\033[31m"
    MAGENTA = "\033[35m"
    BLUE = "\033[34m"


_MODEL_COLORS: dict[str, Color] = {
    "opus": Color.MAGENTA,
    "composer": Color.CYAN,
    "system": Color.BLUE,
}


def _ts() -> str:
    return datetime.now().strftime("%H:%M:%S")


def _emit(prefix: str, color: Color, msg: str, *, dim: bool = False) -> None:
    style = Color.DIM.value if dim else ""
    print(
        f"{Color.DIM.value}{_ts()}{Color.RESET.value} "
        f"{color.value}{Color.BOLD.value}{prefix}{Color.RESET.value} "
        f"{style}{msg}{Color.RESET.value}",
        flush=True,
    )


def opus(msg: str) -> None:
    _emit("Opus 4.6:", _MODEL_COLORS["opus"], msg)


def composer(msg: str) -> None:
    _emit("Composer 2:", _MODEL_COLORS["composer"], msg)


def system(msg: str) -> None:
    _emit("system:", _MODEL_COLORS["system"], msg)


def success(msg: str) -> None:
    _emit("  ✓", Color.GREEN, msg)


def warn(msg: str) -> None:
    _emit("  !", Color.YELLOW, msg)


def error(msg: str) -> None:
    _emit("  ✗", Color.RED, msg)


def step(label: str) -> None:
    width = 60
    print(
        f"\n{Color.GREEN.value}{Color.BOLD.value}{'─' * width}\n"
        f"  {label}\n"
        f"{'─' * width}{Color.RESET.value}\n",
        flush=True,
    )


def fatal(msg: str) -> None:
    error(msg)
    sys.exit(1)
