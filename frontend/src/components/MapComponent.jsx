import React, { useEffect, useRef } from 'react';
import { Landmark, Trash2 } from 'lucide-react';
import { MapContainer, TileLayer, Marker, Popup, Polyline, Polygon, useMap, FeatureGroup } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { EditControl } from "react-leaflet-draw";
import "leaflet-draw/dist/leaflet.draw.css";
import 'leaflet-draw';

const DRAW_OPTIONS = {
  allowIntersection: false,
  shapeOptions: { color: '#ef4444', fillOpacity: 0.3 }
};

// Fix leaflet default icons
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon-2x.png',
  iconUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-icon.png',
  shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.7.1/images/marker-shadow.png',
});

const MapInstanceCapture = ({ setMapInstance }) => {
  const map = useMap();
  useEffect(() => {
    if (map) setMapInstance(map);
  }, [map, setMapInstance]);
  return null;
};

const ZoomControls = () => {
  const map = useMap();
  return (
    <div className="leaflet-top leaflet-left" style={{ marginTop: '1rem', marginLeft: '1rem' }}>
      <div className="leaflet-control leaflet-bar">
        <a className="leaflet-control-zoom-in" href="#" title="Zoom in" onClick={(e) => { e.preventDefault(); map.zoomIn(); }}>+</a>
        <a className="leaflet-control-zoom-out" href="#" title="Zoom out" onClick={(e) => { e.preventDefault(); map.zoomOut(); }}>-</a>
      </div>
    </div>
  );
};

export default function MapComponent({
  startCoords,
  endCoords,
  routePoints = [],
  avoidancePolygon,
  onPolygonCreated,
  onPolygonDeleted,
  setMapInstance
}) {
  const center = startCoords || [20.5937, 78.9629];
  const drawControlRef = useRef(null);

  // Standard OSM tiles as requested ("don't change map colors")
  const osmUrl = "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png";
  const attribution = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

  const handleCreated = (e) => {
    const { layerType, layer } = e;
    if (layerType === 'polygon') {
      const geojson = layer.toGeoJSON();
      onPolygonCreated?.(geojson.geometry);
    }
  };

  const handleStartPolygon = () => {
    console.log("Draw Hazard Zone triggered");
    if (!drawControlRef.current) return;
    const map = drawControlRef.current._map;
    if (!map) return;
    
    // Cleanup any existing tool
    if (map._tool) {
      map._tool.disable();
    }
    
    // Start drawing a polygon programmatically
    const polygonDrawer = new L.Draw.Polygon(map, DRAW_OPTIONS);
    polygonDrawer.enable();
    
    // Store in map so we can disable if needed
    map._tool = polygonDrawer;
  };

  const handleClearPolygons = () => {
    if (drawControlRef.current) {
      drawControlRef.current.clearLayers();
    }
    onPolygonDeleted?.();
  };

  return (
    <MapContainer
      center={center}
      zoom={5}
      style={{ width: '100%', height: '100%' }}
      zoomControl={false}
    >
      <MapInstanceCapture setMapInstance={setMapInstance} />
      
      <TileLayer url={osmUrl} attribution={attribution} />

      <ZoomControls />

      {startCoords && <Marker position={startCoords}><Popup>Start</Popup></Marker>}
      {endCoords && <Marker position={endCoords}><Popup>Destination</Popup></Marker>}

      {routePoints.length > 0 && (
        <Polyline
          positions={routePoints}
          color="#3b82f6"
          weight={6}
          opacity={0.8}
        />
      )}

      <FeatureGroup ref={drawControlRef}>
        <EditControl
          position="topright"
          onCreated={handleCreated}
          onDeleted={onPolygonDeleted}
          draw={{
            polyline: false,
            polygon: false,
            circle: false,
            rectangle: false,
            marker: false,
            circlemarker: false,
          }}
        />
      </FeatureGroup>

      <div className="draw-controls-container">
        <button onClick={handleStartPolygon} className="draw-poly-btn">
          <Landmark size={14} style={{ marginRight: '4px' }} /> Draw Hazard Zone
        </button>
        {avoidancePolygon && (
          <button onClick={handleClearPolygons} className="clear-poly-btn">
            <Trash2 size={14} style={{ marginRight: '4px' }} /> Clear Zone
          </button>
        )}
      </div>
    </MapContainer>
  );
}
