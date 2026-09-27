"""Lab-only temporal descriptions of relative geometric occupancy.

This policy consumes neither image categories nor object detections. A reference
plane is not required; availability of the actual relative-depth evidence is.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from surface_policy import SurfaceObstaclePolicy, ZONE_BOUNDARIES, _integer, _unit


@dataclass(frozen=True)
class DepthObstacleConfig:
    min_candidate_fraction: float = 0.12
    min_consecutive: int = 3
    min_hold_ms: int = 500
    max_gap_ms: int = 1500
    repeat_interval_ms: int = 8000

    def __post_init__(self) -> None:
        if not _unit(self.min_candidate_fraction):
            raise ValueError("min_candidate_fraction must be finite and in [0, 1]")
        if not _integer(self.min_consecutive) or self.min_consecutive < 1:
            raise ValueError("min_consecutive must be a positive integer")
        for name in ("min_hold_ms", "max_gap_ms", "repeat_interval_ms"):
            if not _integer(getattr(self, name)):
                raise ValueError(f"{name} must be a nonnegative 64-bit integer")
        if self.max_gap_ms < 1:
            raise ValueError("max_gap_ms must be positive")

    def as_dict(self) -> dict:
        return {
            "minCandidateFraction": self.min_candidate_fraction,
            "minConsecutive": self.min_consecutive,
            "minHoldMs": self.min_hold_ms,
            "maxGapMs": self.max_gap_ms,
            "repeatIntervalMs": self.repeat_interval_ms,
            "zoneBoundaries": list(ZONE_BOUNDARIES),
            "selection": "confirmed CENTER first; then candidateFraction; LEFT before RIGHT on ties",
            "categoryIndependent": True,
            "detectionsIndependent": True,
            "referencePlaneRequired": False,
            "metric": False,
            "relativeDepthMedianMeaning": "diagnostic within the current frame; not used for confirmation or ranking",
            "meaning": "relative occupancy only; no object identity, metric distance, approach or free-passage conclusion",
        }


class DepthObstaclePolicy(SurfaceObstaclePolicy):
    """Keep strict temporal/quality guards while consuming category-free evidence.

    A complete zone list/map contains candidateFraction and relativeDepthMedian,
    both finite in [0,1]. Candidate fraction alone controls eligibility and score.
    The median remains a diagnostic of this image and cannot be compared across
    independently normalized frames as an approach or proximity measurement.

    A valid zero candidate fraction means no candidate in the provided evidence;
    None means unavailable geometry and breaks confirmation. An unavailable floor
    hypothesis does not by itself invalidate usable relative occupancy. The caller
    owns that availability distinction. Each replay owns one instance.
    """

    config_type = DepthObstacleConfig
    measurement_fields = ("candidateFraction", "relativeDepthMedian")
    policy_version = "rgb-depth-occupancy-v1-experimental"
    proposal_kind = "descriptive_depth_obstacle_candidate"

    @classmethod
    def _zones(cls, values: Any) -> tuple[dict | None, str | None, str | None]:
        # Use the shared strict zone validator, skipping the parent fusion-only
        # subset check (obstructionFraction * depthRelativeSupport). The inherited
        # classmethod stays bound to cls and therefore uses our measurement fields.
        return super(SurfaceObstaclePolicy, cls)._zones(values)

    def _qualifies(self, measurement: dict) -> bool:
        return (measurement["candidateFraction"] > 0
                and measurement["candidateFraction"] >= self.config.min_candidate_fraction)

    @staticmethod
    def _score(measurement: dict) -> float:
        return measurement["candidateFraction"]
