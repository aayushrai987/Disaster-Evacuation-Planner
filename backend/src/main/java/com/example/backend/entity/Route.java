package com.example.backend.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "route", indexes = {
    @Index(name = "idx_start_end", columnList = "start_location, end_location"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
public class Route {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String startLocation;
    private String endLocation;

    // Store the raw GeoJSON string from the API in a TEXT column
    @Column(name = "route_geo_json", columnDefinition = "TEXT")
    private String routeGeometry;

    // Add avoidanceZones field to store JSON string of avoidance coordinates
    @Column(columnDefinition = "TEXT")
    private String avoidanceZones;

    private Double distance;
    private Integer estimatedTime;
    private Double safetyScore;
    
    // JSON: list of intermediate stops [{lat, lng}, ...]
    @Column(columnDefinition = "TEXT")
    private String multiStops;

    // JSON: RRI factor breakdown per route and per-segment
    @Column(columnDefinition = "TEXT")
    private String rriFactors;
    private LocalDateTime createdAt;

    // Automatically set the timestamp when a new route is first saved
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // Constructors
    public Route() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStartLocation() { return startLocation; }
    public void setStartLocation(String startLocation) { this.startLocation = startLocation; }

    public String getEndLocation() { return endLocation; }
    public void setEndLocation(String endLocation) { this.endLocation = endLocation; }

    public String getRouteGeometry() { return routeGeometry; }
    public void setRouteGeometry(String routeGeometry) { this.routeGeometry = routeGeometry; }

    public String getAvoidanceZones() { return avoidanceZones; }
    public void setAvoidanceZones(String avoidanceZones) { this.avoidanceZones = avoidanceZones; }

    public Double getDistance() { return distance; }
    public void setDistance(Double distance) { this.distance = distance; }

    public Integer getEstimatedTime() { return estimatedTime; }
    public void setEstimatedTime(Integer estimatedTime) { this.estimatedTime = estimatedTime; }

    public Double getSafetyScore() { return safetyScore; }
    public void setSafetyScore(Double safetyScore) { this.safetyScore = safetyScore; }

    public String getMultiStops() { return multiStops; }
    public void setMultiStops(String multiStops) { this.multiStops = multiStops; }

    public String getRriFactors() { return rriFactors; }
    public void setRriFactors(String rriFactors) { this.rriFactors = rriFactors; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}