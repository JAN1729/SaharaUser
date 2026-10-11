from datetime import datetime, timedelta, timezone
from enum import StrEnum
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Mode(StrEnum):
    TRAIN = 'train'
    BUS = 'bus'
    FLIGHT = 'flight'
    LOCAL = 'local'
    AUTO = 'auto'
    PRIVATE = 'private'
    METRO = 'metro'
    FERRY = 'ferry'


class Availability(StrEnum):
    AVAILABLE = 'available'
    CONFIRMED = 'confirmed'
    RAC = 'rac'
    WAITLISTED = 'waitlisted'
    SOLD_OUT = 'sold_out'
    UNKNOWN = 'unknown'


class Observation(BaseModel):
    provider: str
    evidence: Literal['live', 'scheduled', 'cached', 'estimated', 'fixture']
    fetched_at: datetime
    observed_at: datetime | None = None
    expires_at: datetime | None = None
    source_url: str | None = None
    parser_version: str = '1'

    @field_validator('fetched_at', 'observed_at', 'expires_at')
    @classmethod
    def aware(cls, value):
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError('Times must include their timezone')
        return value


class Terminal(BaseModel):
    id: str
    name: str
    city: str
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    country: str = 'IN'
    timezone: str = 'Asia/Kolkata'
    kind: str = 'station'
    state: str = 'Kerala'
    codes: dict[str, str] = Field(default_factory=dict)
    aliases: list[str] = Field(default_factory=list)


class Transfer(BaseModel):
    origin: str
    destination: str
    minutes: int = Field(ge=0)
    fare_minor: int | None = Field(default=None, ge=0)
    currency: str = 'INR'
    mode: str = 'walk'
    evidence: Literal['scheduled', 'estimated', 'live', 'fixture'] = 'estimated'
    distance_m: int = Field(default=0, ge=0)
    wheelchair: bool | None = None
    source_url: str | None = None


class Leg(BaseModel):
    id: str
    provider: str
    service_id: str
    name: str
    mode: Mode
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    estimated_departure: datetime | None = None
    estimated_arrival: datetime | None = None
    fare_minor: int | None = Field(default=None, ge=0)
    fare_basis: Literal['per_person', 'party'] = 'per_person'
    currency: str = 'INR'
    availability: Availability = Availability.UNKNOWN
    seats: int | None = Field(default=None, ge=0)
    cancelled: bool = False
    wheelchair: bool | None = None
    booking_url: str | None = None
    booking_opens_at: datetime | None = None
    ticket_group: str | None = None
    fare_class: str | None = None
    observation: Observation
    route_geometry_key:str|None=None
    vehicle_id:str|None=None

    @field_validator('departure', 'arrival', 'estimated_departure', 'estimated_arrival', 'booking_opens_at')
    @classmethod
    def aware(cls, value):
        return Observation.aware(value)

    @model_validator(mode='after')
    def chronology(self):
        if self.arrival <= self.departure:
            raise ValueError('Service arrival must follow departure on an explicit date')
        if self.end <= self.start:
            raise ValueError('Estimated arrival must follow estimated departure')
        return self

    @property
    def start(self):
        return self.estimated_departure or self.departure

    @property
    def end(self):
        return self.estimated_arrival or self.arrival


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    origin: str = Field(min_length=1, max_length=150)
    destination: str = Field(min_length=1, max_length=150)
    depart_after: datetime
    arrive_by: datetime | None = None
    passengers: int = Field(default=1, ge=1, le=9)
    budget_minor: int | None = Field(default=None, ge=0)
    currency: Literal['INR'] = 'INR'
    modes: list[Mode] = Field(default_factory=lambda: list(Mode))
    max_transfers: int = Field(default=2, ge=0, le=3)
    max_walk_m: int = Field(default=2000, ge=0, le=10000)
    connection_buffer_minutes: int = Field(default=15, ge=5, le=180)
    airport_processing_minutes: int = Field(default=120, ge=60, le=360)
    wheelchair: bool = False
    confirmed_only: bool = True
    data_mode: Literal['sample', 'live'] = 'sample'
    preference: Literal['balanced', 'cheapest', 'fastest', 'robust', 'comfortable'] = 'balanced'
    wait_for_complete: bool = False

    @field_validator('depart_after', 'arrive_by')
    @classmethod
    def aware(cls, value):
        return Observation.aware(value)

    @model_validator(mode='after')
    def window(self):
        if self.arrive_by and not self.depart_after < self.arrive_by <= self.depart_after + timedelta(days=3):
            raise ValueError('Arrival deadline must follow departure within 72 hours')
        if not self.modes:
            raise ValueError('Choose at least one travel mode')
        return self


class Connection(BaseModel):
    kind: Literal['access', 'transfer', 'egress'] = 'transfer'
    origin: str
    destination: str
    available_minutes: int
    transfer_minutes: int
    processing_minutes: int
    buffer_minutes: int
    spare_minutes: int
    transfer: Transfer | None = None
    protection: str = 'unknown'


class Journey(BaseModel):
    id: str
    legs: list[Leg]
    connections: list[Connection]
    departure: datetime
    arrival: datetime
    duration_minutes: int
    total_fare_minor: int | None
    known_fare_minor: int
    currency: str
    transfers: int
    warnings: list[str]
    recommendations: list[str] = Field(default_factory=list)
    minimum_spare_minutes: int | None = None


class Rejection(BaseModel):
    leg_id: str
    reason: str


class PlanResult(BaseModel):
    journeys: list[Journey] = Field(default_factory=list)
    rejected: list[Rejection] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    searched_labels: int = 0


class ProviderResult(BaseModel):
    provider: str
    status: Literal['ok', 'empty', 'disabled', 'timeout', 'error', 'partial']
    legs: list[Leg] = Field(default_factory=list)
    message: str = ''
    fetched_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    cached: bool = False
