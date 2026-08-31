"""Forza Horizon 6 virtual powertrain package.

The package is deliberately independent from :mod:`sim`, which contains the
Assetto Corsa implementation.  FH6 assets are always read in place and are
never copied into the repository.
"""

from .config import FH6CarConfig, find_fh6_root, load_reference_config
from .input import PowertrainControl, VehicleSample
from .powertrain import FH6Powertrain, PowertrainFrame

__all__ = [
    "FH6CarConfig",
    "FH6Powertrain",
    "PowertrainControl",
    "PowertrainFrame",
    "VehicleSample",
    "find_fh6_root",
    "load_reference_config",
]
