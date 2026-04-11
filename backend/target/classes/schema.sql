CREATE TABLE IF NOT EXISTS route (
    id SERIAL PRIMARY KEY,
    start_location VARCHAR(255) NOT NULL,
    end_location VARCHAR(255) NOT NULL,
    route_geo_json TEXT NOT NULL,
    avoidance_zones TEXT,
    distance DOUBLE PRECISION,
    estimated_time INTEGER,
    safety_score DOUBLE PRECISION,
    multi_stops TEXT,
    rri_factors TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgrouting;

CREATE TABLE IF NOT EXISTS locations (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100),
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS ways (
    id SERIAL PRIMARY KEY,
    source INTEGER,
    target INTEGER,
    cost DOUBLE PRECISION,
    geom geometry(LineString,4326)
);

CREATE TABLE IF NOT EXISTS ways_vertices_pgr (
    id SERIAL PRIMARY KEY,
    the_geom geometry(Point,4326)
);
