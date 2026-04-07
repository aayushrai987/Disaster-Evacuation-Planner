import React from 'react';

const metersToKm = (m) => m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
const msToMin    = (ms) => `${Math.round(ms / 60000)} min`;

export default function TurnByTurn({ steps = [], onStepHover }) {
  if (steps.length === 0) {
    return (
      <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', fontStyle: 'italic', padding: '0.5rem 0.25rem' }}>
        No turn-by-turn instructions available.
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.2rem' }}>
      {steps.map((s, idx) => (
        <div
          key={idx}
          className="step-row"
          onMouseEnter={() => onStepHover && s.point && onStepHover(s.point)}
        >
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', flex: 1, minWidth: 0 }}>
            <div className="step-num">{idx + 1}</div>
            <div style={{ minWidth: 0 }}>
              <div className="step-text">{s.text || 'Continue'}</div>
              {s.street_name && <div className="step-street">{s.street_name}</div>}
            </div>
          </div>
          <div className="step-meta">
            <div className="step-dist">{metersToKm(s.distance || 0)}</div>
            <div className="step-time">{msToMin(s.time || 0)}</div>
          </div>
        </div>
      ))}
    </div>
  );
}
