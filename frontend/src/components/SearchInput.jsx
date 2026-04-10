import React, { useState, useEffect, useRef, useCallback } from 'react';
import { MapPin, Search, X, Loader2, Clock, Landmark, Home, Coffee, ShoppingCart, TreeDeciduous, Hospital, School, Plane, Train, Fuel } from 'lucide-react';

const getIconForType = (type, category) => {
  const t = (type || '').toLowerCase();
  const c = (category || '').toLowerCase();
  if (t.includes('restaurant') || t.includes('food') || t.includes('cafe')) return <Coffee size={16} />;
  if (t.includes('hotel') || t.includes('hostel') || t.includes('motel')) return <Home size={16} />;
  if (t.includes('hospital') || t.includes('clinic') || t.includes('pharmacy')) return <Hospital size={16} />;
  if (t.includes('school') || t.includes('university') || t.includes('college')) return <School size={16} />;
  if (t.includes('airport')) return <Plane size={16} />;
  if (t.includes('station') || t.includes('railway') || t.includes('bus_stop')) return <Train size={16} />;
  if (t.includes('fuel') || t.includes('gas_station')) return <Fuel size={16} />;
  if (t.includes('shop') || t.includes('store') || t.includes('mall') || t.includes('supermarket')) return <ShoppingCart size={16} />;
  if (t.includes('park') || t.includes('garden') || t.includes('forest') || t.includes('nature')) return <TreeDeciduous size={16} />;
  if (c.includes('amenity') || c.includes('tourism')) return <Landmark size={16} />;
  return <MapPin size={16} />;
};

const formatPlaceName = (result) => {
  const name = result.name || result.display_name.split(',')[0];
  const parts = result.display_name.split(',').map(s => s.trim());
  const secondary = parts.slice(1, 4).join(', ');
  return { primary: name, secondary };
};

let debounceTimer = null;

export default function SearchInput({ value, onChange, placeholder, label, icon: Icon, recentKey = 'recent_search' }) {
  const [query, setQuery] = useState(value || '');
  const [suggestions, setSuggestions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [recent, setRecent] = useState(() => {
    try { return JSON.parse(localStorage.getItem(recentKey) || '[]'); } catch { return []; }
  });
  const containerRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => { setQuery(value || ''); }, [value]);

  useEffect(() => {
    const handler = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const search = useCallback(async (q) => {
    if (!q || q.length < 2) { setSuggestions([]); setLoading(false); return; }
    setLoading(true);
    try {
      const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
      const baseUrl = API_BASE.replace(/\/api$/, '');
      const url = `${baseUrl}/api/geocode?q=${encodeURIComponent(q)}`;
      
      const res = await fetch(url);
      const data = await res.json();
      
      if (data.status === 'success' && data.raw) {
        setSuggestions([data.raw]);
      } else {
        setSuggestions([]);
      }
    } catch (err) {
      console.error('Search error:', err);
      setSuggestions([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleChange = (e) => {
    const q = e.target.value;
    setQuery(q);
    onChange({ label: q, lat: null, lng: null });
    setOpen(true);
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => search(q), 400);
  };

  const handleSelect = (result) => {
    const { primary } = formatPlaceName(result);
    const selected = {
      label: result.display_name,
      shortLabel: primary,
      lat: parseFloat(result.lat),
      lng: parseFloat(result.lon),
    };
    setQuery(result.display_name);
    onChange(selected);
    setSuggestions([]);
    setOpen(false);

    const updated = [selected, ...recent.filter(r => r.label !== selected.label)].slice(0, 5);
    setRecent(updated);
    try { localStorage.setItem(recentKey, JSON.stringify(updated)); } catch {}
  };

  const handleRecentSelect = (item) => {
    setQuery(item.label);
    onChange(item);
    setOpen(false);
  };

  const clearInput = () => {
    setQuery('');
    onChange({ label: '', lat: null, lng: null });
    setSuggestions([]);
    inputRef.current?.focus();
  };

  const showDropdown = open && (loading || suggestions.length > 0 || (query.length < 2 && recent.length > 0));

  return (
    <div ref={containerRef} style={{ position: 'relative', width: '100%' }}>
      {label && <label className="label">{label}</label>}
      <div className="search-input-wrap">
        <span className="search-input-icon">
          {Icon ? <Icon size={16} /> : <Search size={16} />}
        </span>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={handleChange}
          onFocus={() => setOpen(true)}
          placeholder={placeholder || 'Search any place…'}
          className="search-input"
          autoComplete="off"
        />
        {(loading || query) && (
          <span className="search-input-suffix">
            {loading ? <div className="search-spinner" /> : (
              <button className="search-clear-btn" onClick={clearInput} type="button">
                <X size={14} />
              </button>
            )}
          </span>
        )}
      </div>

      {showDropdown && (
        <div className="search-dropdown">
          {query.length < 2 && recent.length > 0 && (
            <>
              <div className="search-section-label"><Clock size={12} /> Recent</div>
              {recent.map((item, i) => (
                <button key={i} className="search-result-item" onClick={() => handleRecentSelect(item)}>
                  <span className="search-result-icon text-muted"><Clock size={16} /></span>
                  <span className="search-result-text">
                    <span className="search-result-primary">{item.shortLabel || item.label.split(',')[0]}</span>
                    <span className="search-result-secondary">{item.label.split(',').slice(1, 3).join(', ')}</span>
                  </span>
                </button>
              ))}
            </>
          )}

          {suggestions.map((result) => {
            const { primary, secondary } = formatPlaceName(result);
            return (
              <button key={result.place_id} className="search-result-item" onClick={() => handleSelect(result)}>
                <span className="search-result-icon">{getIconForType(result.type, result.class)}</span>
                <span className="search-result-text">
                  <span className="search-result-primary">{primary}</span>
                  <span className="search-result-secondary">{secondary}</span>
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
