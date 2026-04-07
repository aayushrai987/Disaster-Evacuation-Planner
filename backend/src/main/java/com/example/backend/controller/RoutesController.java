package com.example.backend.controller;
import com.example.backend.entity.Route;
import com.example.backend.repository.RouteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"})
public class RoutesController {

    @Autowired
    private RouteRepository routeRepository;

    private final RestTemplate restTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RoutesController() {
        this.restTemplate = new RestTemplate();
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add((request, body, execution) -> {
            request.getHeaders().set("User-Agent", "MySafeRouteApp/1.0 (contact@example.com)");
            return execution.execute(request, body);
        });
        this.restTemplate.setInterceptors(interceptors);
    }

    @GetMapping("/safety-breakdown/{id}")
    public ResponseEntity<Map<String, Object>> getSafetyBreakdown(@PathVariable("id") Long id) {
        return routeRepository.findById(id)
                .map(route -> {
                    Map<String, Object> res = new HashMap<>();
                    res.put("status", "success");
                    res.put("safety", route.getSafetyScore());
                    try {
                        res.put("rriFactors", objectMapper.readTree(route.getRriFactors() == null ? "{}" : route.getRriFactors()));
                    } catch (Exception e) {
                        res.put("rriFactors", new HashMap<>());
                    }
                    return ResponseEntity.ok(res);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("status", "error", "message", "Route not found")));
    }

    @GetMapping("/test")
    public String testEndpoint() {
        return "Backend is running!";
    }

    @GetMapping("/geocode")
    public ResponseEntity<Map<String, Object>> geocode(@RequestParam("q") String query) {
        Map<String, Object> out = new HashMap<>();
        try {
            if (query == null || query.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Missing query"));
            }
            String url = "https://nominatim.openstreetmap.org/search" +
                    "?format=json&limit=1&addressdetails=1&q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            // Simple retry for transient 5xx
            int attempts = 0; int max = 3; JsonNode body = null; ResponseEntity<JsonNode> resp = null;
            while (attempts < max) {
                attempts++;
                try {
                    resp = restTemplate.getForEntity(url, JsonNode.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        body = resp.getBody();
                        break;
                    }
                } catch (Exception ex) {
                    try { Thread.sleep(300L * attempts); } catch (InterruptedException ignored) {}
                }
            }
            if (body == null || !body.isArray() || body.size() == 0) {
                return ResponseEntity.status(503).body(Map.of("status", "error", "message", "Geocoding unavailable"));
            }
            JsonNode first = body.get(0);
            double lat = Double.parseDouble(first.get("lat").asText("0"));
            double lon = Double.parseDouble(first.get("lon").asText("0"));
            out.put("status", "success");
            out.put("lat", lat);
            out.put("lng", lon);
            out.put("raw", first);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/route")
    public ResponseEntity<Map<String, Object>> calculateRoute(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            System.out.println("Request received: " + request);
            String startLocation = (String) request.get("startLocation");
            String endLocation = (String) request.get("endLocation");
            List<Double> startCoordsList = (List<Double>) request.get("startCoords");
            List<Double> endCoordsList = (List<Double>) request.get("endCoords");
            Object avoidanceObj = request.get("avoidancePolygon");
            @SuppressWarnings("unchecked")
            List<List<Double>> viaPoints = (List<List<Double>>) request.get("viaPoints");
            Integer requestedAltIndex = null;
            try { requestedAltIndex = request.get("altIndex") != null ? Integer.parseInt(String.valueOf(request.get("altIndex"))) : null; } catch (Exception ignored) {}

            if (startLocation == null || endLocation == null) {
                response.put("status", "error");
                response.put("message", "Start or end location is missing");
                return ResponseEntity.badRequest().body(response);
            }

            double[] startCoords = startCoordsList != null && startCoordsList.size() >= 2
                    ? new double[]{startCoordsList.get(0), startCoordsList.get(1)}
                    : geocodeLocation(startLocation);
            double[] endCoords = endCoordsList != null && endCoordsList.size() >= 2
                    ? new double[]{endCoordsList.get(0), endCoordsList.get(1)}
                    : geocodeLocation(endLocation);

            if (startCoords == null || endCoords == null) {
                response.put("status", "error");
                response.put("message", "Geocoding failed for start or end location");
                return ResponseEntity.badRequest().body(response);
            }
            System.out.println("Start coords: [" + startCoords[0] + ", " + startCoords[1] + "] (lon, lat)");
            System.out.println("End coords: [" + endCoords[0] + ", " + endCoords[1] + "] (lon, lat)");

            Polygon avoidancePolygon = null;
            List<List<double[]>> avoidanceCoords = new ArrayList<>();
            if (avoidanceObj != null) {
                JsonNode avoidanceJson = objectMapper.valueToTree(avoidanceObj);
                if (avoidanceJson.has("coordinates") && avoidanceJson.get("coordinates").isArray()) {
                    JsonNode coordsArray = avoidanceJson.get("coordinates").get(0);
                    List<double[]> polygonPoints = new ArrayList<>();
                    for (JsonNode coord : coordsArray) {
                        if (coord.isArray() && coord.size() >= 2) {
                            polygonPoints.add(new double[]{coord.get(0).asDouble(), coord.get(1).asDouble()});
                        }
                    }
                    if (polygonPoints.size() >= 3) {
                        // Ensure closed ring
                        double[] first = polygonPoints.get(0);
                        double[] last = polygonPoints.get(polygonPoints.size() - 1);
                        if (first[0] != last[0] || first[1] != last[1]) {
                            polygonPoints.add(new double[]{first[0], first[1]});
                        }
                        Polygon rawPoly = createPolygonFromCoords(polygonPoints);
                        // Validate & buffer (~50m ≈ 0.00045 deg)
                        IsValidOp validOp = new IsValidOp(rawPoly);
                        if (!validOp.isValid()) {
                            System.out.println("Avoidance polygon invalid: " + validOp.getValidationError());
                        }
                        // Simplify to limit number of vertices (avoid GraphHopper block_area limits)
                        var simplifiedGeom = TopologyPreservingSimplifier.simplify(rawPoly, 0.0003);
                        Polygon buffered = (Polygon) simplifiedGeom.buffer(0.00045);
                        // Clip to bbox of start/end with margin
                        // Relaxed bbox clipping: allow more space for detours (5.0 degrees ≈ 500km)
                        Envelope env = new Envelope(
                                Math.min(startCoords[0], endCoords[0]) - 5.0,
                                Math.max(startCoords[0], endCoords[0]) + 5.0,
                                Math.min(startCoords[1], endCoords[1]) - 5.0,
                                Math.max(startCoords[1], endCoords[1]) + 5.0);
                        var bboxGeom = geometryFactory.toGeometry(env);
                        var clipped = buffered.intersection(bboxGeom);
                        if (clipped instanceof Polygon) {
                            avoidancePolygon = (Polygon) clipped;
                        } else {
                            avoidancePolygon = buffered;
                        }
                        // Store cleaned coords
                        List<double[]> cleaned = new ArrayList<>();
                        for (Coordinate c : avoidancePolygon.getExteriorRing().getCoordinates()) {
                            cleaned.add(new double[]{c.x, c.y});
                        }
                        avoidanceCoords.add(cleaned);
                        System.out.println("Avoidance polygon created with " + cleaned.size() + " points (buffered & clipped)");
                    } else {
                        System.out.println("Invalid avoidance polygon: fewer than 3 points");
                    }
                } else {
                    System.out.println("Invalid avoidance polygon structure: " + avoidanceJson.toString());
                }
            }

            // GraphHopper API Call
            String apiKey = "5e599a47-2998-4da7-aca8-59ea2294f926";
            String ghUrl;
            ResponseEntity<JsonNode> ghResponseEntity;
            JsonNode ghResponse;
            
            if (!avoidanceCoords.isEmpty() && avoidanceCoords.get(0).size() >= 3) {
                System.out.println("=== AVOIDANCE POLYGON: Using block_area & alternatives ===");

                // Build block_area polygon string in format: lat,lon:lat,lon:...
                StringBuilder blockArea = new StringBuilder();
                List<double[]> poly = avoidanceCoords.get(0);
                for (int i = 0; i < poly.size(); i++) {
                    double[] p = poly.get(i);
                    if (i > 0) blockArea.append(",");
                    blockArea.append(p[1]).append(",").append(p[0]);
                }
                StringBuilder ghParams = new StringBuilder();
                ghParams.append("point=").append(startCoords[1]).append(",").append(startCoords[0]);
                if (viaPoints != null) {
                    for (List<Double> vp : viaPoints) {
                        if (vp != null && vp.size() >= 2)
                            ghParams.append("&point=").append(vp.get(1)).append(",").append(vp.get(0));
                    }
                }
                ghParams.append("&point=").append(endCoords[1]).append(",").append(endCoords[0]);
                ghParams.append("&vehicle=car&calc_points=true&geometries=geojson&points_encoded=false");
                ghParams.append("&alternatives=3&instructions=true");
                ghParams.append("&details=road_class&details=surface&details=road_access&elevation=true");
                ghParams.append("&block_area=").append(java.net.URLEncoder.encode(blockArea.toString(), java.nio.charset.StandardCharsets.UTF_8));

                ghUrl = "https://graphhopper.com/api/1/route?key=" + apiKey + "&" + ghParams.toString();
                System.out.println("Fetching route with block_area: " + ghUrl);
                ghResponseEntity = restTemplate.getForEntity(ghUrl, JsonNode.class);
                ghResponse = ghResponseEntity.getBody();
            } else {
                // Use GET request without avoidance
                StringBuilder ghParams = new StringBuilder();
                ghParams.append("point=").append(startCoords[1]).append(",").append(startCoords[0]);
                if (viaPoints != null) {
                    for (List<Double> vp : viaPoints) {
                        if (vp != null && vp.size() >= 2)
                            ghParams.append("&point=").append(vp.get(1)).append(",").append(vp.get(0));
                    }
                }
                ghParams.append("&point=").append(endCoords[1]).append(",").append(endCoords[0]);
                ghParams.append("&vehicle=car&calc_points=true&geometries=geojson&points_encoded=false");
                ghParams.append("&alternatives=3&instructions=true");
                ghParams.append("&details=road_class&details=surface&details=road_access&elevation=true");
                
                ghUrl = "https://graphhopper.com/api/1/route?key=" + apiKey + "&" + ghParams.toString();
                System.out.println("GET URL (no avoidance): " + ghUrl);
                ghResponseEntity = restTemplate.getForEntity(ghUrl, JsonNode.class);
                ghResponse = ghResponseEntity.getBody();
            }
            System.out.println("=== GRAPHHOPPER RESPONSE ===");
            if (ghResponse != null) {
                System.out.println("Full response: " + ghResponse.toString());
                if (ghResponse.has("paths")) {
                    JsonNode paths = ghResponse.get("paths");
                    System.out.println("Number of paths returned: " + paths.size());
                    if (paths.size() > 0) {
                        JsonNode firstPath = paths.get(0);
                        if (firstPath.has("distance")) {
                            System.out.println("Route distance: " + firstPath.get("distance").asDouble() + " meters");
                        }
                        if (firstPath.has("points")) {
                            JsonNode points = firstPath.get("points");
                            if (points.has("coordinates")) {
                                System.out.println("Number of route coordinates: " + points.get("coordinates").size());
                            }
                        }
                    }
                }
            } else {
                System.out.println("GraphHopper returned null response!");
            }

            if (ghResponse != null && ghResponse.has("paths") && !ghResponse.get("paths").isEmpty()) {
                // Choose best path: prioritize no intersection with avoidancePolygon, else minimal intersection
                JsonNode pathsNode = ghResponse.get("paths");
                int bestIdx = 0;
                double bestScore = Double.POSITIVE_INFINITY; // lower is better: 0 for no intersection, then ratio
                for (int pi = 0; pi < pathsNode.size(); pi++) {
                    JsonNode p = pathsNode.get(pi);
                    JsonNode g = p.get("points");
                    if (g == null || !g.has("coordinates") || g.get("coordinates").size() < 2) continue;
                    JsonNode coordsN = g.get("coordinates");
                    Coordinate[] lcs = new Coordinate[coordsN.size()];
                    for (int i = 0; i < coordsN.size(); i++) {
                        JsonNode c = coordsN.get(i);
                        lcs[i] = new Coordinate(c.get(0).asDouble(), c.get(1).asDouble());
                    }
                    LineString ls = geometryFactory.createLineString(lcs);
                    double score = 0.0;
                    if (avoidancePolygon != null && ls.intersects(avoidancePolygon)) {
                        var inter = ls.intersection(avoidancePolygon);
                        double interLen = 0.0;
                        if (inter.getNumGeometries() > 1) {
                            LineMerger merger = new LineMerger();
                            merger.add(inter);
                            @SuppressWarnings("unchecked")
                            Collection<LineString> merged = (Collection<LineString>) merger.getMergedLineStrings();
                            for (LineString il : merged) interLen += il.getLength();
                        } else if (inter instanceof LineString) {
                            interLen = ((LineString) inter).getLength();
                        }
                        score = interLen / Math.max(1e-9, ls.getLength());
                    }
                    if (score < bestScore) { bestScore = score; bestIdx = pi; }
                }
                // If caller requested a specific alternative and it's valid, prefer it
                if (requestedAltIndex != null && requestedAltIndex >= 0 && requestedAltIndex < pathsNode.size()) {
                    bestIdx = requestedAltIndex;
                }

                // If still intersecting significantly, try forced detours using waypoints around centroid
                boolean forcedDetourTried = false;
                if (avoidancePolygon != null && bestScore > 1e-6) {
                    forcedDetourTried = true;
                    Coordinate startC = new Coordinate(startCoords[0], startCoords[1]);
                    Coordinate endC = new Coordinate(endCoords[0], endCoords[1]);
                    Coordinate centroid = avoidancePolygon.getCentroid().getCoordinate();
                    double vx = endC.x - startC.x;
                    double vy = endC.y - startC.y;
                    double len = Math.sqrt(vx*vx + vy*vy);
                    if (len < 1e-9) len = 1e-9;
                    // normal vector (perpendicular)
                    double nx = -vy / len;
                    double ny = vx / len;
                    double offset = 0.6; // ~60 km in lon/lat approx (rough), adjust as needed
                    // two detour candidates on both sides of the polygon
                    double[][] detours = new double[][]{
                        { centroid.x + nx * offset, centroid.y + ny * offset },
                        { centroid.x - nx * offset, centroid.y - ny * offset }
                    };
                    double detourBestScore = bestScore;
                    JsonNode detourBestResponse = null;
                    for (double[] d : detours) {
                        StringBuilder ghParams2 = new StringBuilder();
                        ghParams2.append("point=").append(startCoords[1]).append(",").append(startCoords[0]);
                        ghParams2.append("&point=").append(d[1]).append(",").append(d[0]);
                        ghParams2.append("&point=").append(endCoords[1]).append(",").append(endCoords[0]);
                        ghParams2.append("&vehicle=car&calc_points=true&geometries=geojson&points_encoded=false");
                        ghParams2.append("&alternatives=1&instructions=true");
                        ghParams2.append("&details=road_class&details=surface&details=road_access&elevation=true");
                        if (!avoidanceCoords.isEmpty()) {
                            // reuse block_area string from above branch if present
                            StringBuilder blockArea2 = new StringBuilder();
                            List<double[]> poly2 = avoidanceCoords.get(0);
                            for (int i = 0; i < poly2.size(); i++) {
                                double[] p = poly2.get(i);
                                if (i > 0) blockArea2.append(",");
                                blockArea2.append(p[1]).append(",").append(p[0]);
                            }
                            ghParams2.append("&block_area=")
                                .append(java.net.URLEncoder.encode(blockArea2.toString(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                        String url2 = "https://graphhopper.com/api/1/route?key=" + apiKey + "&" + ghParams2.toString();
                        try {
                            ResponseEntity<JsonNode> detourResp = restTemplate.getForEntity(url2, JsonNode.class);
                            JsonNode body = detourResp.getBody();
                            if (body != null && body.has("paths") && body.get("paths").size() > 0) {
                                JsonNode p0 = body.get("paths").get(0);
                                JsonNode g0 = p0.get("points");
                                if (g0 != null && g0.has("coordinates")) {
                                    JsonNode coordsN0 = g0.get("coordinates");
                                    Coordinate[] lcs0 = new Coordinate[coordsN0.size()];
                                    for (int i = 0; i < coordsN0.size(); i++) {
                                        JsonNode c0 = coordsN0.get(i);
                                        lcs0[i] = new Coordinate(c0.get(0).asDouble(), c0.get(1).asDouble());
                                    }
                                    LineString ls0 = geometryFactory.createLineString(lcs0);
                                    double score0 = 0.0;
                                    if (avoidancePolygon != null && ls0.intersects(avoidancePolygon)) {
                                        var inter0 = ls0.intersection(avoidancePolygon);
                                        double interLen0 = 0.0;
                                        if (inter0.getNumGeometries() > 1) {
                                            LineMerger merger0 = new LineMerger();
                                            merger0.add(inter0);
                                            @SuppressWarnings("unchecked")
                                            Collection<LineString> merged0 = (Collection<LineString>) merger0.getMergedLineStrings();
                                            for (LineString il0 : merged0) interLen0 += il0.getLength();
                                        } else if (inter0 instanceof LineString) {
                                            interLen0 = ((LineString) inter0).getLength();
                                        }
                                        score0 = interLen0 / Math.max(1e-9, ls0.getLength());
                                    }
                                    if (score0 < detourBestScore) {
                                        detourBestScore = score0;
                                        detourBestResponse = body;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            System.out.println("Detour attempt failed: " + ex.getMessage());
                        }
                    }
                    if (detourBestResponse != null) {
                        ghResponse = detourBestResponse; // replace with detour response
                        pathsNode = ghResponse.get("paths");
                        bestIdx = 0;
                        bestScore = detourBestScore;
                    }
                }

                // Build alternative summaries and geometries
                List<Map<String, Object>> altSummaries = new ArrayList<>();
                List<Object> altGeometries = new ArrayList<>();
                for (int pi = 0; pi < pathsNode.size(); pi++) {
                    JsonNode p = pathsNode.get(pi);
                    double distKm = p.has("distance") ? p.get("distance").asDouble(0) / 1000.0 : 0.0;
                    double timeMin = p.has("time") ? p.get("time").asDouble(0) / 60000.0 : 0.0;
                    Map<String, Object> rriAlt = computeRriAndBreakdown(p, avoidanceCoords.isEmpty() ? 0 : 1);
                    Map<String, Object> sum = new HashMap<>();
                    sum.put("index", pi);
                    sum.put("distanceKm", distKm);
                    sum.put("timeMin", timeMin);
                    sum.put("safety", rriAlt.get("safetyScore"));
                    altSummaries.add(sum);
                    // capture geometry for preview
                    if (p.has("points") && p.get("points").has("coordinates")) {
                        altGeometries.add(p.get("points"));
                    }
                }

                JsonNode path = pathsNode.get(bestIdx);
                JsonNode geometryNode = path.get("points");
                if (geometryNode == null || !geometryNode.has("coordinates") || geometryNode.get("coordinates").size() < 2) {
                    response.put("status", "error");
                    response.put("message", "Invalid route geometry from GraphHopper");
                    return ResponseEntity.badRequest().body(response);
                }

                JsonNode coords = geometryNode.get("coordinates");
                Coordinate[] lineCoords = new Coordinate[coords.size()];
                for (int i = 0; i < coords.size(); i++) {
                    JsonNode coord = coords.get(i);
                    lineCoords[i] = new Coordinate(coord.get(0).asDouble(), coord.get(1).asDouble());
                }
                LineString routeLine = geometryFactory.createLineString(lineCoords);

                double intersectionRatio = 0.0;
                if (avoidancePolygon != null && routeLine.intersects(avoidancePolygon)) {
                    var intersection = routeLine.intersection(avoidancePolygon);
                    double intersectLength = 0.0;
                    if (intersection.getNumGeometries() > 1) {
                        LineMerger merger = new LineMerger();
                        merger.add(intersection);
                        @SuppressWarnings("unchecked")
                        Collection<LineString> mergedLines = (Collection<LineString>) merger.getMergedLineStrings();
                        for (LineString ls : mergedLines) {
                            intersectLength += ls.getLength();
                        }
                    } else if (intersection instanceof LineString) {
                        intersectLength = ((LineString) intersection).getLength();
                    }
                    intersectionRatio = intersectLength / routeLine.getLength();
                    System.out.println("Route intersects avoidance by " + String.format("%.2f%%", intersectionRatio * 100));
                }

                boolean allSame = true;
                double routeDistance = path.get("distance").asDouble();
                if (routeDistance > 100) {
                    Coordinate first = lineCoords[0];
                    for (int i = 1; i < lineCoords.length; i++) {
                        if (!lineCoords[i].equals(first)) {
                            allSame = false;
                            break;
                        }
                    }
                } else {
                    allSame = false;
                }
                if (allSame) {
                    response.put("status", "error");
                    response.put("message", "No valid route found: Checkpoints too close or outside map data");
                    return ResponseEntity.badRequest().body(response);
                }

                String geoJSON = geometryNode.toString();
                double distance = routeDistance / 1000;
                double duration = path.get("time").asDouble() / 60000;
                // Enhanced safety score using details if available
                Map<String, Object> rri = computeRriAndBreakdown(path, avoidanceCoords.isEmpty() ? 0 : 1);
                double safetyScore = (double) rri.get("safetyScore");

                Route routeEntity = new Route();
                routeEntity.setStartLocation(startLocation);
                routeEntity.setEndLocation(endLocation);
                routeEntity.setRouteGeometry(geoJSON);
                routeEntity.setAvoidanceZones(objectMapper.writeValueAsString(avoidanceCoords));
                routeEntity.setDistance(distance);
                routeEntity.setEstimatedTime((int) Math.round(duration));
                routeEntity.setSafetyScore(safetyScore);
                routeEntity.setRriFactors(objectMapper.writeValueAsString(rri));
                routeRepository.save(routeEntity);

                response.put("status", "success");
                response.put("message", "Route calculated successfully" + (avoidanceCoords.isEmpty() ? "" : " (avoidance applied)"));
                response.put("routeGeoJSON", geometryNode);
                response.put("distance", String.format("%.1f km", distance));
                response.put("time", String.format("%d min", (int) Math.round(duration)));
                response.put("safety", safetyScore);
                // Debug flags to aid frontend verification
                response.put("pickedAlternativeIndex", bestIdx);
                response.put("usedBlockArea", !avoidanceCoords.isEmpty());
                // Include instructions for turn-by-turn UI with anchor points
                if (path.has("instructions") && path.get("instructions").isArray()) {
                    List<Map<String, Object>> steps = new ArrayList<>();
                    JsonNode coordsNode = geometryNode.get("coordinates");
                    for (JsonNode step : path.get("instructions")) {
                        Map<String, Object> s = new HashMap<>();
                        s.put("text", step.has("text") ? step.get("text").asText("") : "");
                        s.put("distance", step.has("distance") ? step.get("distance").asDouble(0.0) : 0.0);
                        s.put("time", step.has("time") ? step.get("time").asLong(0L) : 0L);
                        s.put("street_name", step.has("street_name") ? step.get("street_name").asText("") : "");
                        if (step.has("interval") && step.get("interval").isArray() && step.get("interval").size() >= 1) {
                            int idx = Math.max(0, step.get("interval").get(0).asInt(0));
                            if (coordsNode != null && idx < coordsNode.size()) {
                                JsonNode c = coordsNode.get(idx);
                                Map<String, Object> pt = new HashMap<>();
                                pt.put("lng", c.get(0).asDouble());
                                pt.put("lat", c.get(1).asDouble());
                                s.put("point", pt);
                            }
                        }
                        steps.add(s);
                    }
                    response.put("instructions", steps);
                }
                response.put("rri", rri.get("rri"));
                response.put("rriFactors", rri.get("factors"));
                response.put("pickedAlternativeIndex", bestIdx);
                response.put("alternatives", altSummaries);
                response.put("altGeometries", altGeometries);
                if (intersectionRatio > 0) {
                    response.put("warning", "Route partially intersects avoidance area (" + String.format("%.1f%%", intersectionRatio * 100) + ")");
                }
                return ResponseEntity.ok(response);
            } else {
                System.out.println("GraphHopper failed, using mock data");
                response.put("status", "success");
                response.put("message", "Using mock data (GraphHopper failed)");
                response.put("routeGeoJSON", createMockGeoJSON(startCoords, endCoords, avoidancePolygon));
                response.put("distance", "297.1 km");
                response.put("time", "369 min");
                response.put("safety", 84.06);
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("status", "error");
            response.put("message", "An error occurred: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private double[] geocodeLocation(String location) {
        if ("Bhopal".equalsIgnoreCase(location)) return new double[]{77.4126, 23.2599};
        if ("Jabalpur".equalsIgnoreCase(location)) return new double[]{79.9499, 23.1673};
        if ("Central Zone".equalsIgnoreCase(location)) return new double[]{78.0, 23.5};
        if ("Test Start".equalsIgnoreCase(location)) return new double[]{77.296414, 28.549334};
        if ("Test End".equalsIgnoreCase(location)) return new double[]{77.308226, 28.59738};
        return null;
    }

    private Polygon createPolygonFromCoords(List<double[]> coordsList) {
        if (coordsList.size() < 3) return null;
        Coordinate[] coordinates = new Coordinate[coordsList.size() + 1];
        for (int i = 0; i < coordsList.size(); i++) {
            double[] pt = coordsList.get(i);
            coordinates[i] = new Coordinate(pt[0], pt[1]);
        }
        coordinates[coordsList.size()] = coordinates[0];
        return geometryFactory.createPolygon(coordinates);
    }

    private double calculateSafetyScore(double distanceKm, double intersectionRatio, int hasAvoidance) {
        double baseScore = 95.0;
        double distancePenalty = Math.min(distanceKm / 50, 10.0);
        double avoidancePenalty = hasAvoidance * 5.0;
        double intersectionPenalty = intersectionRatio * 50.0;
        return Math.max(0.0, Math.min(100.0, baseScore - distancePenalty - avoidancePenalty - intersectionPenalty));
    }

    private Map<String, Object> computeRriAndBreakdown(JsonNode path, int avoidanceCount) {
        Map<String, Object> out = new HashMap<>();
        double roadRisk = 0.3; // default
        double elevationRisk = 0.0; // placeholder
        double avoidanceFactor = Math.min(1.0, avoidanceCount * 0.2);
        double dynamicRisk = 0.0; // TODO: integrate traffic/weather providers

        if (path.has("details")) {
            JsonNode details = path.get("details");
            // road_class weighting
            if (details.has("road_class")) {
                JsonNode rc = details.get("road_class");
                int n = rc.size();
                int motorwayCount = 0;
                int residentialCount = 0;
                for (int i = 0; i < n; i++) {
                    JsonNode seg = rc.get(i);
                    // seg structure: [from, to, value]
                    if (seg.size() >= 3) {
                        String value = seg.get(2).asText("");
                        if ("motorway".equals(value) || "trunk".equals(value)) motorwayCount++;
                        if ("residential".equals(value) || "living_street".equals(value)) residentialCount++;
                    }
                }
                int total = Math.max(1, n);
                double motorwayShare = (double) motorwayCount / total;
                double residentialShare = (double) residentialCount / total;
                roadRisk = 0.8 * motorwayShare + 0.2 * residentialShare + 0.4 * (1 - motorwayShare - residentialShare);
            }
            // surface & elevation could adjust risks slightly
            if (details.has("surface")) {
                JsonNode surf = details.get("surface");
                int n = surf.size();
                int bad = 0;
                for (int i = 0; i < n; i++) {
                    String v = surf.get(i).get(2).asText("");
                    if ("gravel".equals(v) || "unpaved".equals(v)) bad++;
                }
                elevationRisk += Math.min(0.2, bad / Math.max(1.0, n) * 0.2);
            }
        }

        double rri = 0.4 * roadRisk + 0.3 * elevationRisk + 0.2 * avoidanceFactor + 0.1 * dynamicRisk;
        rri = Math.max(0.0, Math.min(1.0, rri));
        double safetyScore = Math.max(0.0, Math.min(100.0, 100.0 * (1.0 - rri)));
        // bonuses
        if (path.has("details") && path.get("details").has("road_access")) {
            // naive: if any emergency_access present, add bonus
            JsonNode ra = path.get("details").get("road_access");
            for (int i = 0; i < ra.size(); i++) {
                String v = ra.get(i).get(2).asText("");
                if (v.contains("emergency")) { safetyScore = Math.min(100.0, safetyScore + 10.0); break; }
            }
        }
        // small low-traffic bonus placeholder
        safetyScore = Math.min(100.0, safetyScore + 5.0);

        out.put("rri", rri);
        out.put("safetyScore", safetyScore);
        Map<String, Object> factors = new HashMap<>();
        factors.put("roadRisk", roadRisk);
        factors.put("elevationRisk", elevationRisk);
        factors.put("avoidanceFactor", avoidanceFactor);
        factors.put("dynamicRisk", dynamicRisk);
        out.put("factors", factors);
        return out;
    }


    private JsonNode createMockGeoJSON(double[] startCoords, double[] endCoords, Polygon avoidancePolygon) {
        List<List<Double>> coordinates = new ArrayList<>();
        coordinates.add(List.of(startCoords[0], startCoords[1])); // [lon, lat]
        double midLon = (startCoords[0] + endCoords[0]) / 2;
        double midLat = (startCoords[1] + endCoords[1]) / 2;
        
        if (avoidancePolygon != null) {
            Coordinate midPoint = new Coordinate(midLon, midLat);
            if (avoidancePolygon.contains(geometryFactory.createPoint(midPoint))) {
                // Shift away from the centroid of the avoidance polygon
                double centroidLon = avoidancePolygon.getCentroid().getX();
                double centroidLat = avoidancePolygon.getCentroid().getY();
                // Calculate a direction away from the polygon center
                double distLon = midLon - centroidLon;
                double distLat = midLat - centroidLat;
                // If centroid is too close (midpoint is inside polygon), move by a fixed offset
                if (Math.abs(distLon) < 0.01 && Math.abs(distLat) < 0.01) {
                    midLon += 0.3; // Move east
                    midLat += 0.3; // Move north
                } else {
                    double scale = 1.5;
                    midLon += distLon * scale;
                    midLat += distLat * scale;
                }
                System.out.println("Mock route avoiding polygon: shifted midpoint from centroid");
            }
        }
        coordinates.add(List.of(midLon, midLat));
        coordinates.add(List.of(endCoords[0], endCoords[1]));

        Map<String, Object> geoJSON = new HashMap<>();
        geoJSON.put("type", "LineString");
        geoJSON.put("coordinates", coordinates);
        return objectMapper.valueToTree(geoJSON);
    }
}
