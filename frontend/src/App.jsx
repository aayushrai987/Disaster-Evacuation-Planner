import React, { useState, useEffect, useCallback, Component } from 'react';
import axios from 'axios';
import { 
  Navigation, Shield, Cloud, Menu, X, 
  ChevronRight, Map as MapIcon, Info, MapPin, 
  ArrowRight, ThermometerSun, Wind
} from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import MapComponent from './components/MapComponent';
import SafetyDashboard from './components/SafetyDashboard';
import TurnByTurn from './components/TurnByTurn';
import AlternativesList from './components/AlternativesList';
import SearchInput from './components/SearchInput';

// ── Safe number helpers ──────────────────────────────────────
const safeFix = (val, decimals = 1) => {
  const n = parseFloat(val);
  return isNaN(n) ? '—' : n.toFixed(decimals);
};

// ── Error Boundary ───────────────────────────────────────────
class ErrorBoundary extends Component {
  constructor(props) { super(props); this.state = { error: null }; }
  static getDerivedStateFromError(err) { return { error: err }; }
  componentDidCatch(err, info) { console.error('React crash:', err, info); }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: '1rem', color: '#ef4444', fontSize: '0.8rem', background: '#1e293b', borderRadius: 8, margin: '0.5rem' }}>
          <strong>Display error:</strong> {this.state.error.message}
          <button
            onClick={() => this.setState({ error: null })}
            style={{ display: 'block', marginTop: 8, padding: '4px 10px', background: '#334155', border: 'none', color: '#fff', borderRadius: 4, cursor: 'pointer' }}
          >
            Dismiss
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

// ── Main App ─────────────────────────────────────────────────
export default function App() {
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
  const [startPlace, setStartPlace] = useState({ label: '', lat: null, lng: null });
  const [endPlace, setEndPlace]     = useState({ label: '', lat: null, lng: null });
  const [viaStops, setViaStops]     = useState([]);
  const [disasterType, setDisasterType] = useState('Fire / Wildfire');
  const [theme, setTheme]           = useState(() => localStorage.getItem('theme') || 'dark');
  const [loading, setLoading]       = useState(false);
  const [locating, setLocating]     = useState(false);
  const [error, setError]           = useState('');
  const [routeData, setRouteData]   = useState(null);
  const [weather, setWeather]       = useState(null);
  const [avoidancePolygon, setAvoidancePolygon] = useState(null);
  const [mapInstance, setMapInstance] = useState(null);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('theme', theme);
  }, [theme]);

  const toggleTheme = () => setTheme(t => t === 'dark' ? 'light' : 'dark');

  // ── GPS location ───────────────────────────────────────────
  const useMyLocation = () => {
    if (!navigator.geolocation) return setError('Geolocation not supported.');
    setLocating(true);
    setError('');
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        const { latitude: lat, longitude: lng } = pos.coords;
        try {
          const r = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lng}&addressdetails=1`, { headers: { 'Accept-Language': 'en' } });
          const data = await r.json();
          const name = (data.display_name || 'Current Location').split(',')[0];
          setStartPlace({ label: data.display_name || 'Current Location', shortLabel: name, lat, lng });
        } catch {
          setStartPlace({ label: 'Current Location', lat, lng });
        }
        setLocating(false);
      },
      () => { setError('Location access denied.'); setLocating(false); },
      { timeout: 8000 }
    );
  };

  // ── Weather ────────────────────────────────────────────────
  const fetchWeather = async (lat, lon) => {
    try {
      const res = await axios.get(
        `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current_weather=true`
      );
      if (res.data?.current_weather) setWeather(res.data.current_weather);
    } catch (e) { console.warn('Weather fetch failed:', e); }
  };

  // ── Reusable Search Function ──────────────────────────────────
  const performSearch = async (overrideAvoidance = null) => {
    if (!startPlace.lat || !endPlace.lat) return;
    
    setLoading(true);
    setError('');
    const activeAvoidance = overrideAvoidance !== undefined ? overrideAvoidance : avoidancePolygon;

    const payload = {
      startLocation: startPlace.label,
      endLocation:   endPlace.label,
      startCoords:   [startPlace.lng, startPlace.lat],
      endCoords:     [endPlace.lng, endPlace.lat],
      viaPoints:     viaStops.map(s => [s.lng, s.lat]).filter(p => p[0] && p[1]),
      avoidancePolygon: activeAvoidance,
      disasterType,
    };

    const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
    try {
      // If API_BASE already ends in /api, we don't add it again
      const baseUrl = API_BASE.endsWith('/api') ? API_BASE : `${API_BASE}/api`;
      const res = await axios.post(`${baseUrl}/route`, payload, { timeout: 30000 });
      const data = res.data || {};

      const mapped = {
        ...data,
        distanceKm: data.distance ? parseFloat(data.distance) : null,
        timeMin: data.time ? parseFloat(data.time) : null,
        safetyScore: data.safety ?? null,
        steps: data.instructions || [],
        routePoints: (() => {
          try {
            const coords = data.routeGeoJSON?.coordinates;
            if (Array.isArray(coords) && coords.length > 0) {
              return coords.map(([lng, lat]) => [lat, lng]);
            }
          } catch {}
          return [];
        })(),
      };

      setRouteData(mapped);
      const sc = payload.startCoords;
      if (sc?.[1] && sc?.[0]) fetchWeather(sc[1], sc[0]);

      if (mapInstance && mapped.routePoints.length > 1) {
        try { mapInstance.fitBounds(mapped.routePoints, { padding: [60, 60] }); } catch {}
      }
    } catch (err) {
      console.error('Route error:', err);
      setError(err.response?.data?.message || 'Route calculation failed.');
    } finally {
      setLoading(false);
    }
  };

  // ── Route search event handler ────────────────────────────────
  const handleSearch = (e) => {
    e?.preventDefault();
    if (!startPlace.lat || !endPlace.lat) {
      return setError('Please select locations from the dropdown suggestions.');
    }
    performSearch(avoidancePolygon);
  };

  // Auto-update when polygon changes
  useEffect(() => {
    if (startPlace.lat && endPlace.lat) {
      performSearch(avoidancePolygon);
    }
  }, [avoidancePolygon, startPlace, endPlace, viaStops, disasterType]);


  return (
    <div className="app-root">
      {/* Sidebar re-open button */}
      <AnimatePresence>
        {!isSidebarOpen && (
          <motion.button
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.8 }}
            className="open-sidebar-btn"
            onClick={() => setIsSidebarOpen(true)}
          >
            <Menu size={20} />
          </motion.button>
        )}
      </AnimatePresence>

      {/* ── Sidebar ── */}
      <motion.aside
        initial={false}
        animate={{ width: isSidebarOpen ? 400 : 0, minWidth: isSidebarOpen ? 400 : 0, opacity: isSidebarOpen ? 1 : 0 }}
        transition={{ duration: 0.3 }}
        className="sidebar"
        style={{ overflow: 'hidden' }}
      >
        <div className="sidebar-header">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h1 className="sidebar-title">Disaster Evacuation Planner</h1>
          </div>
        </div>

        <div className="sidebar-body">
          {/* Plan Form */}
          <div className="panel">
            <div className="panel-title"><Navigation size={15} className="icon" /> Plan Evacuation</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>

              <div className="input-row">
                <SearchInput
                  label="From"
                  placeholder="Starting point…"
                  value={startPlace.label}
                  onChange={setStartPlace}
                  icon={MapPin}
                  recentKey="recent_from"
                />
                <button onClick={useMyLocation} disabled={locating} className="loc-btn" style={{ marginTop: '1.55rem' }}>
                  {locating ? <div className="search-spinner" /> : <MapPin size={16} />}
                </button>
              </div>

              {viaStops.map((stop, idx) => (
                <div key={idx} className="input-row">
                  <SearchInput
                    label={`Via stop ${idx + 1}`}
                    placeholder="Waypoint…"
                    value={stop.label}
                    onChange={(val) => {
                      const updated = [...viaStops]; updated[idx] = val; setViaStops(updated);
                    }}
                    recentKey={`recent_via_${idx}`}
                  />
                  <button className="btn-icon" style={{ marginTop: '1.55rem' }} onClick={() => setViaStops(v => v.filter((_, i) => i !== idx))}>
                    <X size={14} />
                  </button>
                </div>
              ))}

              <SearchInput
                label="Destination"
                placeholder="Safe location?"
                value={endPlace.label}
                onChange={setEndPlace}
                icon={ChevronRight}
                recentKey="recent_to"
              />

              <button
                className="btn-secondary"
                style={{ alignSelf: 'flex-start' }}
                onClick={() => setViaStops(v => [...v, { label: '', lat: null, lng: null }])}
              >
                <MapIcon size={14} /> Add Via Stop
              </button>

              <div>
                <label className="label">Disaster Context</label>
                <select
                  className="input-field disaster-select"
                  value={disasterType}
                  onChange={e => setDisasterType(e.target.value)}
                >
                  <option value="General Emergency">General Emergency</option>
                  <option value="Flood / Ocean">Flood / Ocean</option>
                  <option value="Fire / Wildfire">Fire / Wildfire</option>
                  <option value="Industrial Hazard">Industrial Hazard</option>
                </select>
              </div>

              <button className="btn-primary" onClick={handleSearch} disabled={loading}>
                {loading ? <><div className="search-spinner" style={{ marginRight: 8 }} /> Calculating…</> : 'Search Evacuation Route'}
              </button>

              {error && <div className="error-msg">{error}</div>}
            </div>
          </div>

          {/* Route Stats */}
          {routeData && (
            <ErrorBoundary>
              <div className="panel slide-in">
                <div className="panel-title"><Shield size={15} className="icon" /> Route Statistics</div>
                <div className="stat-grid">
                  <div className="stat-card">
                    <div className="stat-value">{safeFix(routeData.distanceKm)}</div>
                    <div className="stat-label">KM</div>
                  </div>
                  <div className="stat-card">
                    <div className="stat-value">{safeFix(routeData.timeMin != null ? routeData.timeMin / 60 : null)}</div>
                    <div className="stat-label">Hours</div>
                  </div>
                  <div className="stat-card" style={{ borderColor: 'var(--success)' }}>
                    <div className="stat-value" style={{ color: 'var(--success)' }}>
                      {routeData.safetyScore != null ? `${parseFloat(routeData.safetyScore).toFixed(2)}%` : '—'}
                    </div>
                    <div className="stat-label">Safety</div>
                  </div>
                </div>
              </div>
            </ErrorBoundary>
          )}

          {/* Risk Factors */}
          {routeData && (
            <ErrorBoundary>
              <div className="panel slide-in">
                <div className="panel-title"><Info size={15} className="icon" /> Risk Analysis</div>
                <SafetyDashboard rri={routeData.rri} factors={routeData.rriFactors || {}} />
              </div>
            </ErrorBoundary>
          )}

          {/* Weather */}
          {weather && (
            <ErrorBoundary>
              <div className="panel slide-in">
                <div className="panel-title"><Cloud size={15} className="icon" /> Area Forecast</div>
                <div className="weather-card">
                  <div className="weather-temp">{Math.round(weather.temperature ?? 0)}°</div>
                  <div className="weather-info">
                    <div className="weather-label">Wind: {weather.windspeed ?? '—'} km/h</div>
                    {weather.weathercode > 50 && (
                      <div className="weather-alert">Risk of severe weather</div>
                    )}
                  </div>
                </div>
              </div>
            </ErrorBoundary>
          )}
        </div>

        {/* Footer / Theme toggle */}
        <div className="sidebar-footer">
          <button className="theme-toggle-btn" onClick={toggleTheme}>
            {theme === 'dark' ? <ThermometerSun size={16} /> : <Wind size={16} />}
            Switch to {theme === 'dark' ? 'Light' : 'Dark'} Mode
          </button>
        </div>
      </motion.aside>

      {/* ── Map ── */}
      <main className="map-wrapper" style={{ flex: 1, height: '100vh', position: 'relative' }}>
        <ErrorBoundary>
          <MapComponent
            startCoords={startPlace.lat ? [startPlace.lat, startPlace.lng] : null}
            endCoords={endPlace.lat   ? [endPlace.lat,   endPlace.lng]   : null}
            routePoints={routeData?.routePoints || []}
            avoidancePolygon={avoidancePolygon}
            onPolygonCreated={setAvoidancePolygon}
            onPolygonDeleted={() => setAvoidancePolygon(null)}
            theme={theme}
            setMapInstance={setMapInstance}
          />
        </ErrorBoundary>

        {/* Turn-by-turn floating panel */}
        <AnimatePresence>
          {routeData && (
            <motion.div
              key="turn-panel"
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 20 }}
              className="floating-panel"
              style={{ bottom: 20, right: 20, width: 360, maxHeight: 420, overflowY: 'auto' }}
            >
              <div className="floating-panel-header">
                <div className="floating-panel-title"><ArrowRight size={14} /> Turn-by-Turn</div>
                <button className="close-btn" onClick={() => setRouteData(null)}>
                  <X size={14} />
                </button>
              </div>
              <ErrorBoundary>
                <TurnByTurn steps={Array.isArray(routeData.steps) ? routeData.steps : []} />
              </ErrorBoundary>
            </motion.div>
          )}

          {/* Alternative routes */}
          {routeData?.alternatives?.length > 0 && (
            <motion.div
              key="alt-panel"
              initial={{ opacity: 0, y: -20 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -20 }}
              className="floating-panel"
              style={{ top: 20, right: 20, width: 300 }}
            >
              <div className="floating-panel-header">
                <div className="floating-panel-title"><MapIcon size={14} /> Alt. Routes</div>
              </div>
              <ErrorBoundary>
                <AlternativesList items={routeData.alternatives} selectedIndex={0} />
              </ErrorBoundary>
            </motion.div>
          )}
        </AnimatePresence>
      </main>
    </div>
  );
}