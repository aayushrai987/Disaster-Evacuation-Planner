import React from 'react';

const safetyColor = (v) => {
  if (v >= 80) return 'var(--success)';
  if (v >= 60) return 'var(--warning)';
  return 'var(--danger)';
};

export default function AlternativesList({ items = [], selectedIndex = 0, onSelect, onHover }) {
  if (items.length === 0) {
    return <div style={{ fontSize: '0.78rem', color: 'var(--text-faint)', fontStyle: 'italic' }}>No alternatives.</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem' }}>
      {items.map((alt) => (
        <button
          key={alt.index}
          onClick={() => onSelect?.(alt.index)}
          onMouseEnter={() => onHover?.(alt.index)}
          onMouseLeave={() => onHover?.(null)}
          className={`alt-btn${selectedIndex === alt.index ? ' selected' : ''}`}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div className="alt-btn-name">Route {alt.index + 1}</div>
            <div style={{ fontSize: '0.72rem', fontWeight: 700, color: safetyColor(alt.safety) }}>
              <span
                className="alt-safety-dot"
                style={{ background: safetyColor(alt.safety) }}
              />
              {typeof alt.safety === 'number' ? alt.safety.toFixed(0) : '—'}% safe
            </div>
          </div>
          <div className="alt-btn-meta">
            {(alt.distanceKm || 0).toFixed(1)} km &bull; {(alt.timeMin || 0).toFixed(0)} min
          </div>
        </button>
      ))}
    </div>
  );
}
