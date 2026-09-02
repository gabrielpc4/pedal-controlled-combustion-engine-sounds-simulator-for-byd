# Source-bank auxiliary audio

The direct FMOD runtime discovers the events genuinely present in each car bank. It drives the
engine, transmission, turbo, limiter, gear, backfire, traction-control, gear-grind, and start events
from physical state when those events exist. It does not synthesize a replacement or substitute an
event from another car.

The app supplies only the parameters and lifecycle transitions the authored graph expects. It does
not force throttle/load, replace event volume, or expose gain, enable/disable, mute, or solo
overrides. FMOD Studio automation therefore owns the relative contribution of every recording.

`tires`, `wind`, `chassis`, and `doors` are intentionally excluded because they describe the game
world rather than the requested powertrain sound. The read-only mixer reports the exact playable
events and raw sources discovered at runtime.
