import React from 'react';

const entries = [
  { key: 'roadRisk',       label: 'Road Risk',       color: '#f59e0b', bg: 'rgba(245,158,11,0.15)' },
  { key: 'elevationRisk',  label: 'Elevation Risk',  color: '#10b981', bg: 'rgba(16,185,129,0.15)' },
  { key: 'avoidanceFactor',label: 'Avoidance Factor',color: '#ef4444', bg: 'rgba(239,68,68,0.15)' },
  { key: 'dynamicRisk',    label: 'Dynamic Risk',    color: '#6366f1', bg: 'rgba(99,102,241,0.15)' },
];

export default function SafetyDashboard({ rri = 0, factors = {} }) {
  const rriVal = rri || 0;
  const rriClass = rriVal < 0.3 ? 'good' : rriVal < 0.6 ? 'medium' : 'bad';
  const rriLabel = rriVal < 0.3 ? '✓ Low Risk' : rriVal < 0.6 ? '⚠ Medium Risk' : '✕ High Risk';

  return (
    <div>
      <div className={`rri-badge ${rriClass}`}>
        {rriLabel} &mdash; RRI: {rriVal.toFixed(2)}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.625rem' }}>
        {entries.map(({ key, label, color, bg }) => {
          const v = Math.max(0, Math.min(1, Number(factors[key] || 0)));
          return (
            <div key={key} className="safety-row">
              <div className="safety-row-header">
                <span className="safety-row-label">{label}</span>
                <span className="safety-row-value" style={{ color }}>{(v * 100).toFixed(0)}%</span>
              </div>
              <div className="safety-bar-track">
                <div
                  className="safety-bar-fill"
                  style={{ width: `${v * 100}%`, background: `linear-gradient(90deg, ${color}cc, ${color})` }}
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
