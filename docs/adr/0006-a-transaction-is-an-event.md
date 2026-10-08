# 0006 — A transaction is an event, addressed by one record's uuid

Status: Accepted

## Decision

Expose each linked buy/sell or transfer as one transaction event. Address it by an existing
record UUID: the investment-account half for buys/sells and the outgoing half for transfers.
Accept the other half's UUID as an alias, returning the canonical identity.

## Reason

The user edits one event in a dialog even when the model persists two records. Exposing those
records separately would make clients reconstruct the event and invite counting it twice. It
would also suggest that one half can be edited or deleted independently.

A synthetic event ID would avoid choosing a half, but require new persisted identity and migration
solely for this interface. The existing records already have stable IDs. Accepting either half
lets callers follow record references without knowing the model's canonicalization rule.

## Consequences

Lists contain one item per event. Ownership filters must inspect both endpoints before deciding
whether an event matches. Metadata comes from the canonical record; this API does not conceal
linked identity by inventing another record or expose the halves as independent resources.
