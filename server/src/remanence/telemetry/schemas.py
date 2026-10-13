"""Strict coarse geometry/chooser data, never free-form metadata."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

STYLES = {
    "g5": {"press", "blue-letter", "poster"}, "g6": {"signal", "night-air"},
    "g11": {"plum", "construction", "pool"}, "g12": {"telemetry", "folio"},
    "g17": {"espresso", "aubergine", "lacquer"}, "g18": {"proof", "ochre"},
    "g20": {"evergreen", "studio"}, "g21": {"ink-letter", "correspondence"},
    "g22": {"apricot", "mineral", "brass"}, "g23": {"glacier", "ultraviolet"},
    "g24": {"orchid", "ember"}, "g25": {"darkroom", "rose-stock", "registration"},
    "g26": {"saffron", "blue-hour"}, "g27": {"nocturne", "porcelain"},
}
Index = Annotated[int, Field(ge=0, le=127)]
FaceCoordinate = Annotated[int, Field(ge=0, le=16)]
CropCoordinate = Annotated[int, Field(ge=0, le=10000)]
FaceBox = Annotated[list[FaceCoordinate], Field(min_length=4, max_length=4)]
CropBox = Annotated[list[CropCoordinate], Field(min_length=4, max_length=4)]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, hide_input_in_errors=True)


class Variant(StrictModel):
    grammar: str = Field(max_length=3)
    style: str = Field(max_length=16)

    @model_validator(mode="after")
    def allowlisted_pair(self):
        if self.style not in STYLES.get(self.grammar, ()):
            raise ValueError("unsupported variant")
        return self


class Design(StrictModel):
    photo_count: int = Field(ge=1, le=12)
    text_bucket: Literal["none", "short", "long"]
    music: bool
    cover: bool
    available: list[Variant] = Field(min_length=1, max_length=128)
    shown: list[Index] = Field(max_length=256)
    selected: Index
    sequence_truncated: bool

    @model_validator(mode="after")
    def valid_indices(self):
        if self.selected >= len(self.available) or any(i >= len(self.available) for i in self.shown):
            raise ValueError("invalid variant index")
        if any(a == b for a, b in zip(self.shown, self.shown[1:])):
            raise ValueError("consecutive appearances must be deduplicated")
        return self


def valid_box(box: list[int], scale: int) -> bool:
    x, y, w, h = box
    return w > 0 and h > 0 and x + w <= scale and y + h <= scale


class Crop(Variant):
    slot: int = Field(ge=0, le=11)
    aspect_bucket: Literal["portrait", "square", "landscape", "panorama"]
    faces: list[FaceBox] = Field(max_length=16)
    focus_grid: list[Annotated[int, Field(ge=0, le=15)]] = Field(min_length=64, max_length=64)
    initial: CropBox
    final: CropBox
    edited: bool

    @model_validator(mode="after")
    def bounded_geometry(self):
        if not all(valid_box(box, 16) for box in self.faces):
            raise ValueError("invalid face box")
        if not valid_box(self.initial, 10000) or not valid_box(self.final, 10000):
            raise ValueError("invalid crop box")
        if not self.edited and self.initial != self.final:
            raise ValueError("unchanged crop differs")
        return self


class TelemetryReport(StrictModel):
    schema_version: Literal[1]
    crop_policy: Literal["balanced-v1"]
    analysis_version: Literal["import-field-3.3"]
    design: Design | None = None
    crops: list[Crop] | None = Field(default=None, min_length=1, max_length=12)

    @field_validator("schema_version", mode="before")
    @classmethod
    def actual_integer_version(cls, value):
        if type(value) is not int:
            raise ValueError("invalid schema version")
        return value

    @model_validator(mode="after")
    def components(self):
        if self.design is None and self.crops is None:
            raise ValueError("empty report")
        if self.crops is not None:
            if len({c.slot for c in self.crops}) != len(self.crops):
                raise ValueError("duplicate slot")
            if self.design is not None:
                variant = self.design.available[self.design.selected]
                if any(c.slot >= self.design.photo_count or c.grammar != variant.grammar or
                       c.style != variant.style for c in self.crops):
                    raise ValueError("crop does not belong to selected candidate")
        return self
