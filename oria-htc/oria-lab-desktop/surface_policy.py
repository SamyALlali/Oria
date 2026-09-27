"""Experimental RGB wall descriptions for Lab review, never a navigation verdict.

No I/O, model, real-time clock, speech or depth estimate is used here. Each replay
job owns one instance; backwards seeking starts a new instance or calls reset().
"""
from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Any


ZONES = ("LEFT", "CENTER", "RIGHT")
ZONE_BOUNDARIES = (0.39, 0.61)
MAX_INT64 = (1 << 63) - 1
PHRASES = {
    "LEFT": "Mur probable à gauche",
    "CENTER": "Mur probable devant",
    "RIGHT": "Mur probable à droite",
}


def _integer(value: Any) -> bool:
    return type(value) is int and 0 <= value <= MAX_INT64


def _unit(value: Any) -> bool:
    return type(value) in (int, float) and 0 <= value <= 1 and math.isfinite(value)


@dataclass(frozen=True)
class SurfaceWallConfig:
    """Provisional inspection thresholds, not calibrated safety thresholds."""

    min_wall_fraction: float = 0.35
    min_wall_confidence: float = 0.60
    min_consecutive: int = 3
    min_hold_ms: int = 500
    max_gap_ms: int = 1500
    repeat_interval_ms: int = 8000

    def __post_init__(self) -> None:
        for name in ("min_wall_fraction", "min_wall_confidence"):
            if not _unit(getattr(self, name)):
                raise ValueError(f"{name} must be finite and in [0, 1]")
        if not _integer(self.min_consecutive) or self.min_consecutive < 1:
            raise ValueError("min_consecutive must be a positive integer")
        for name in ("min_hold_ms", "max_gap_ms", "repeat_interval_ms"):
            if not _integer(getattr(self, name)):
                raise ValueError(f"{name} must be a nonnegative 64-bit integer")
        if self.max_gap_ms < 1:
            raise ValueError("max_gap_ms must be positive")

    def as_dict(self) -> dict:
        return {
            "minWallFraction": self.min_wall_fraction,
            "minWallMeanConfidence": self.min_wall_confidence,
            "minConsecutive": self.min_consecutive,
            "minHoldMs": self.min_hold_ms,
            "maxGapMs": self.max_gap_ms,
            "repeatIntervalMs": self.repeat_interval_ms,
            "zoneBoundaries": list(ZONE_BOUNDARIES),
            "selection": "confirmed CENTER first; then fraction*confidence; LEFT before RIGHT on ties",
            "confidenceMeaning": "uncalibrated mean wall softmax among argmax wall pixels",
        }


@dataclass
class _Evidence:
    consecutive: int = 0
    first_at_ms: int | None = None
    last_proposal_at_ms: int | None = None


class SurfaceWallPolicy:
    """Descriptive proposals on the capture clock, with independent zone evidence.

    ``zones`` is a complete three-item list from the segmenter, or a mapping from
    LEFT/CENTER/RIGHT to those values. None, empty or incomplete input is missing
    data, *not* an observation of no wall. Only a complete valid zero-fraction
    observation expresses no wall candidate. That never means the route is clear.

    Repeated/reordered frames or clocks break evidence but cannot rewind the
    accepted high-water marks. Call reset() before replaying an earlier position.
    Missing/invalid zone data with a valid identity advances these high-water
    marks and breaks evidence. A valid new typed session identity resets all state.
    """

    config_type = SurfaceWallConfig
    measurement_fields = ("wallFraction", "wallMeanConfidence")
    policy_version = "rgb-wall-descriptive-v1-experimental"
    phrases = PHRASES
    proposal_kind = "descriptive_wall_candidate"

    def __init__(self, config: SurfaceWallConfig | SurfaceObstacleConfig | None = None):
        self.config = config if config is not None else self.config_type()
        if not isinstance(self.config, self.config_type):
            raise TypeError(f"config must be {self.config_type.__name__}")
        self.reset()

    def reset(self) -> None:
        self._session_key: tuple[type, str | int] | None = None
        self._last_frame: int | None = None
        self._last_at_ms: int | None = None
        self._evidence = {zone: _Evidence() for zone in ZONES}

    def _break_evidence(self) -> None:
        for evidence in self._evidence.values():
            evidence.consecutive = 0
            evidence.first_at_ms = None

    @classmethod
    def _zones(cls, values: Any) -> tuple[dict | None, str | None, str | None]:
        if values is None:
            return None, "missing", "observation_missing"
        if isinstance(values, dict):
            if not values:
                return None, "missing", "observation_missing"
            if any(zone not in ZONES for zone in values):
                return None, "invalid", "unknown_zone"
            if set(values) != set(ZONES):
                return None, "missing", "zone_missing"
            items = [(zone, values[zone]) for zone in ZONES]
        elif isinstance(values, list):
            if not values:
                return None, "missing", "observation_missing"
            items = []
            seen = set()
            for value in values:
                if not isinstance(value, dict) or type(value.get("zone")) is not str:
                    return None, "invalid", "invalid_zone_entry"
                zone = value["zone"]
                if zone not in ZONES:
                    return None, "invalid", "unknown_zone"
                if zone in seen:
                    return None, "invalid", "duplicate_zone"
                seen.add(zone)
                items.append((zone, value))
            if seen != set(ZONES):
                return None, "missing", "zone_missing"
        else:
            return None, "invalid", "invalid_observation_type"
        normalized = {}
        for zone, value in items:
            if not isinstance(value, dict):
                return None, "invalid", "invalid_zone_entry"
            if "zone" in value and value["zone"] != zone:
                return None, "invalid", "conflicting_zone_identity"
            if any(field not in value for field in cls.measurement_fields):
                return None, "missing", "zone_measurement_missing"
            if any(not _unit(value[field]) for field in cls.measurement_fields):
                return None, "invalid", "invalid_zone_measurement"
            normalized[zone] = {field: float(value[field]) for field in cls.measurement_fields}
        return normalized, None, None

    def _qualifies(self, measurement: dict) -> bool:
        return (measurement["wallFraction"] > 0
                and measurement["wallFraction"] >= self.config.min_wall_fraction
                and measurement["wallMeanConfidence"] >= self.config.min_wall_confidence)

    @staticmethod
    def _score(measurement: dict) -> float:
        return measurement["wallFraction"] * measurement["wallMeanConfidence"]

    def process(self, session_id: str | int, frame_index: int, observed_at_ms: int, zones: Any) -> dict:
        output = {
            "schemaVersion": 1,
            "policyVersion": self.policy_version,
            "labOnly": True,
            "audioEmitted": False,
            "metricDepthAvailable": False,
            "poseAvailable": False,
            "sessionId": session_id if ((type(session_id) is str and bool(session_id.strip())) or _integer(session_id)) else None,
            "frameIndex": frame_index if _integer(frame_index) else None,
            "observedAtMs": observed_at_ms if _integer(observed_at_ms) else None,
            "status": "invalid",
            "reason": "invalid_identity",
            "proposal": None,
            "resetGap": False,
            "resetReason": None,
            "evidence": [],
            "config": self.config.as_dict(),
        }
        valid_session = (type(session_id) is str and bool(session_id.strip())) or _integer(session_id)
        if not valid_session or not _integer(frame_index) or not _integer(observed_at_ms):
            self._break_evidence()
            output["resetReason"] = "invalid_identity"
            return output
        session_key = (type(session_id), session_id)
        if self._session_key != session_key:
            output["resetReason"] = "session_started" if self._session_key is None else "session_changed"
            self.reset()
            self._session_key = session_key
        elif self._last_frame is not None:
            if frame_index <= self._last_frame:
                self._break_evidence()
                output.update(status="rejected", reason="duplicate_frame" if frame_index == self._last_frame else "out_of_order_frame", resetReason="frame_order")
                return output
            if observed_at_ms <= self._last_at_ms:
                self._break_evidence()
                output.update(status="rejected", reason="timestamp_not_increasing", resetReason="clock_order")
                return output
            if frame_index != self._last_frame + 1:
                self._break_evidence()
                output["resetReason"] = "frame_gap"
            if observed_at_ms - self._last_at_ms > self.config.max_gap_ms:
                self._break_evidence()
                output.update(resetGap=True, resetReason="observation_gap")
        self._last_frame = frame_index
        self._last_at_ms = observed_at_ms
        normalized, bad_status, bad_reason = self._zones(zones)
        if normalized is None:
            self._break_evidence()
            output.update(status=bad_status, reason=bad_reason)
            if output["resetReason"] is None:
                output["resetReason"] = "missing_data" if bad_status == "missing" else "invalid_data"
            return output
        confirmed = []
        candidates = []
        for zone in ZONES:
            measurement = normalized[zone]
            evidence = self._evidence[zone]
            qualifies = self._qualifies(measurement)
            if qualifies:
                if evidence.consecutive == 0:
                    evidence.first_at_ms = observed_at_ms
                evidence.consecutive += 1
                candidates.append(zone)
            else:
                evidence.consecutive = 0
                evidence.first_at_ms = None
            duration = observed_at_ms - evidence.first_at_ms if evidence.first_at_ms is not None else 0
            stable = qualifies and evidence.consecutive >= self.config.min_consecutive and duration >= self.config.min_hold_ms
            if stable:
                confirmed.append(zone)
            cooldown = max(0, self.config.repeat_interval_ms - (observed_at_ms - evidence.last_proposal_at_ms)) if evidence.last_proposal_at_ms is not None else 0
            output["evidence"].append({"zone": zone, **measurement, "score": self._score(measurement),
                "qualifies": qualifies, "consecutive": evidence.consecutive, "heldMs": duration,
                "confirmed": stable, "cooldownRemainingMs": cooldown})
        if not confirmed:
            output.update(status="observing" if candidates else "no_candidate", reason="warming_up" if candidates else "below_thresholds")
            return output
        # Select before checking cooldown: a side cannot displace a confirmed
        # central description merely because that description was just proposed.
        winner = min(confirmed, key=lambda zone: (0 if zone == "CENTER" else 1,
            -self._score(normalized[zone]), ZONES.index(zone)))
        evidence = self._evidence[winner]
        if evidence.last_proposal_at_ms is not None and observed_at_ms - evidence.last_proposal_at_ms < self.config.repeat_interval_ms:
            output.update(status="cooldown", reason="repeat_interval", selectedZone=winner)
            return output
        evidence.last_proposal_at_ms = observed_at_ms
        next(item for item in output["evidence"] if item["zone"] == winner)["cooldownRemainingMs"] = self.config.repeat_interval_ms
        output.update(status="proposal", reason="confirmed_candidate", selectedZone=winner,
            proposal={"zone": winner, "text": self.phrases[winner], "observedAtMs": observed_at_ms,
                      "kind": self.proposal_kind, "audioEmitted": False})
        return output


@dataclass(frozen=True)
class SurfaceObstacleConfig:
    """Heuristics for inspecting fusion evidence, never metric proximity."""

    min_obstruction_fraction: float = 0.20
    min_unrecognized_fraction: float = 0.12
    min_depth_relative_support: float = 0.60
    min_consecutive: int = 3
    min_hold_ms: int = 500
    max_gap_ms: int = 1500
    repeat_interval_ms: int = 8000

    def __post_init__(self) -> None:
        for name in ("min_obstruction_fraction", "min_unrecognized_fraction", "min_depth_relative_support"):
            if not _unit(getattr(self, name)):
                raise ValueError(f"{name} must be finite and in [0, 1]")
        if not _integer(self.min_consecutive) or self.min_consecutive < 1:
            raise ValueError("min_consecutive must be a positive integer")
        for name in ("min_hold_ms", "max_gap_ms", "repeat_interval_ms"):
            if not _integer(getattr(self, name)):
                raise ValueError(f"{name} must be a nonnegative 64-bit integer")
        if self.max_gap_ms < 1:
            raise ValueError("max_gap_ms must be positive")

    def as_dict(self) -> dict:
        return {
            "minObstructionFraction": self.min_obstruction_fraction,
            "minUnrecognizedFraction": self.min_unrecognized_fraction,
            "minDepthRelativeSupport": self.min_depth_relative_support,
            "minConsecutive": self.min_consecutive,
            "minHoldMs": self.min_hold_ms,
            "maxGapMs": self.max_gap_ms,
            "repeatIntervalMs": self.repeat_interval_ms,
            "zoneBoundaries": list(ZONE_BOUNDARIES),
            "selection": "confirmed CENTER first; then unrecognizedFraction*depthRelativeSupport; LEFT before RIGHT on ties",
            "depthMeaning": "per-frame relative relief support; no metric distance or calibrated probability",
        }


class SurfaceObstaclePolicy(SurfaceWallPolicy):
    """Propose generic candidates from validated segmentation/depth/YOLO fusion.

    ``obstructionFraction`` describes non-background pixels in the ROI/zone;
    ``unrecognizedFraction`` describes retained relative-relief components outside
    known YOLO boxes, divided by ROI/zone area; ``depthRelativeSupport`` is the
    fraction of non-background pixels above the relative-relief threshold before
    YOLO masking. These are geometric fractions, not probabilities or distances.

    The caller must pass None when depth or recorded YOLO evidence is absent. A
    real, available YOLO inference with zero detections may still yield candidates.
    This module does not estimate these values or infer upstream availability.
    """

    config_type = SurfaceObstacleConfig
    measurement_fields = ("obstructionFraction", "unrecognizedFraction", "depthRelativeSupport")
    policy_version = "rgb-obstacle-descriptive-v1-experimental"
    proposal_kind = "descriptive_obstacle_candidate"
    phrases = {"LEFT": "Obstacle possible à gauche", "CENTER": "Obstacle possible devant", "RIGHT": "Obstacle possible à droite"}

    @classmethod
    def _zones(cls, values: Any) -> tuple[dict | None, str | None, str | None]:
        normalized, status, reason = super()._zones(values)
        if normalized is not None:
            for measurement in normalized.values():
                # The remaining components are a subset of relief-supported
                # non-background pixels, with the same ROI/zone denominator.
                maximum = measurement["obstructionFraction"] * measurement["depthRelativeSupport"]
                if measurement["unrecognizedFraction"] > maximum + 1e-9:
                    return None, "invalid", "inconsistent_zone_fractions"
        return normalized, status, reason

    def _qualifies(self, measurement: dict) -> bool:
        return (measurement["unrecognizedFraction"] > 0
                and measurement["obstructionFraction"] >= self.config.min_obstruction_fraction
                and measurement["unrecognizedFraction"] >= self.config.min_unrecognized_fraction
                and measurement["depthRelativeSupport"] >= self.config.min_depth_relative_support)

    @staticmethod
    def _score(measurement: dict) -> float:
        return measurement["unrecognizedFraction"] * measurement["depthRelativeSupport"]
